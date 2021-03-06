package com.github.msemys.esjc;

import com.github.msemys.esjc.event.Event;
import com.github.msemys.esjc.event.Events;
import com.github.msemys.esjc.node.EndpointDiscoverer;
import com.github.msemys.esjc.node.NodeEndpoints;
import com.github.msemys.esjc.node.cluster.ClusterDnsEndpointDiscoverer;
import com.github.msemys.esjc.node.static_.StaticEndpointDiscoverer;
import com.github.msemys.esjc.operation.*;
import com.github.msemys.esjc.operation.manager.OperationItem;
import com.github.msemys.esjc.operation.manager.OperationManager;
import com.github.msemys.esjc.ssl.CommonNameTrustManagerFactory;
import com.github.msemys.esjc.subscription.*;
import com.github.msemys.esjc.subscription.manager.SubscriptionItem;
import com.github.msemys.esjc.subscription.manager.SubscriptionManager;
import com.github.msemys.esjc.system.SystemEventType;
import com.github.msemys.esjc.system.SystemStreams;
import com.github.msemys.esjc.task.*;
import com.github.msemys.esjc.tcp.ChannelId;
import com.github.msemys.esjc.tcp.TcpPackage;
import com.github.msemys.esjc.tcp.TcpPackageDecoder;
import com.github.msemys.esjc.tcp.TcpPackageEncoder;
import com.github.msemys.esjc.tcp.handler.AuthenticationHandler;
import com.github.msemys.esjc.tcp.handler.AuthenticationHandler.AuthenticationStatus;
import com.github.msemys.esjc.tcp.handler.HeartbeatHandler;
import com.github.msemys.esjc.tcp.handler.OperationHandler;
import com.github.msemys.esjc.transaction.TransactionManager;
import com.github.msemys.esjc.util.Strings;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

import static com.github.msemys.esjc.system.SystemStreams.isMetastream;
import static com.github.msemys.esjc.util.EmptyArrays.EMPTY_BYTES;
import static com.github.msemys.esjc.util.Numbers.isNegative;
import static com.github.msemys.esjc.util.Numbers.isPositive;
import static com.github.msemys.esjc.util.Preconditions.checkArgument;
import static com.github.msemys.esjc.util.Preconditions.checkNotNull;
import static com.github.msemys.esjc.util.Strings.*;
import static com.github.msemys.esjc.util.Threads.sleepUninterruptibly;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.time.Duration.between;
import static java.time.Instant.now;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class EventStoreImpl implements EventStore {
    private static final Logger logger = LoggerFactory.getLogger(EventStore.class);

    private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;
    protected static final int MAX_READ_SIZE = 4 * 1024;

    private enum ConnectionState {INIT, CONNECTING, CONNECTED, CLOSED}

    private enum ConnectingPhase {INVALID, RECONNECTING, ENDPOINT_DISCOVERY, CONNECTION_ESTABLISHING, AUTHENTICATION, CONNECTED}

    private final EventLoopGroup group = new NioEventLoopGroup(0, new DefaultThreadFactory("esio"));
    private final Bootstrap bootstrap;
    private final OperationManager operationManager;
    private final SubscriptionManager subscriptionManager;
    private final Settings settings;

    private volatile Channel connection;
    private volatile ConnectingPhase connectingPhase = ConnectingPhase.INVALID;

    private volatile ScheduledFuture timer;
    private final TransactionManager transactionManager = new TransactionManagerImpl();
    private final TaskQueue tasks;
    private final EndpointDiscoverer discoverer;
    private final ReconnectionInfo reconnectionInfo = new ReconnectionInfo();
    private Instant lastOperationTimeoutCheck = Instant.MIN;

    private final Set<EventStoreListener> listeners = new CopyOnWriteArraySet<>();

    private final Object mutex = new Object();

    public EventStoreImpl(Settings settings) {
        checkNotNull(settings, "settings");

        bootstrap = new Bootstrap()
            .option(ChannelOption.SO_KEEPALIVE, settings.tcpSettings.keepAlive)
            .option(ChannelOption.TCP_NODELAY, settings.tcpSettings.tcpNoDelay)
            .option(ChannelOption.SO_SNDBUF, settings.tcpSettings.sendBufferSize)
            .option(ChannelOption.SO_RCVBUF, settings.tcpSettings.receiveBufferSize)
            .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, settings.tcpSettings.writeBufferLowWaterMark)
            .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, settings.tcpSettings.writeBufferHighWaterMark)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) settings.tcpSettings.connectTimeout.toMillis())
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ChannelPipeline pipeline = ch.pipeline();

                    if (settings.sslSettings.useSslConnection) {
                        SslContext sslContext = SslContextBuilder.forClient()
                            .trustManager(settings.sslSettings.validateServerCertificate ?
                                new CommonNameTrustManagerFactory(settings.sslSettings.certificateCommonName) :
                                InsecureTrustManagerFactory.INSTANCE)
                            .build();
                        pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
                    }

                    // decoder
                    pipeline.addLast("frame-decoder", new LengthFieldBasedFrameDecoder(LITTLE_ENDIAN, MAX_FRAME_LENGTH, 0, 4, 0, 4, true));
                    pipeline.addLast("package-decoder", new TcpPackageDecoder());

                    // encoder
                    pipeline.addLast("frame-encoder", new LengthFieldPrepender(LITTLE_ENDIAN, 4, 0, false));
                    pipeline.addLast("package-encoder", new TcpPackageEncoder());

                    // logic
                    pipeline.addLast("idle-state-handler", new IdleStateHandler(0, settings.heartbeatInterval.toMillis(), 0, MILLISECONDS));
                    pipeline.addLast("heartbeat-handler", new HeartbeatHandler(settings.heartbeatTimeout));
                    pipeline.addLast("authentication-handler", new AuthenticationHandler(settings.userCredentials, settings.operationTimeout)
                        .whenComplete(EventStoreImpl.this::onAuthenticationCompleted));
                    pipeline.addLast("operation-handler", new OperationHandler(operationManager, subscriptionManager)
                        .whenBadRequest(EventStoreImpl.this::onBadRequest)
                        .whenChannelError(EventStoreImpl.this::onChannelError)
                        .whenReconnect(EventStoreImpl.this::onReconnect));
                }
            });

        operationManager = new OperationManager(settings);
        subscriptionManager = new SubscriptionManager(settings);

        this.settings = settings;

        if (settings.staticNodeSettings.isPresent()) {
            discoverer = new StaticEndpointDiscoverer(settings.staticNodeSettings.get(), settings.sslSettings.useSslConnection);
        } else if (settings.clusterNodeSettings.isPresent()) {
            discoverer = new ClusterDnsEndpointDiscoverer(settings.clusterNodeSettings.get(), group);
        } else {
            throw new IllegalStateException("Node settings not found");
        }

        tasks = new TaskQueue(executor());
        tasks.register(StartConnection.class, this::handle);
        tasks.register(CloseConnection.class, this::handle);
        tasks.register(EstablishTcpConnection.class, this::handle);
        tasks.register(StartOperation.class, this::handle);
        tasks.register(StartSubscription.class, this::handle);
        tasks.register(StartPersistentSubscription.class, this::handle);
    }

    @Override
    public CompletableFuture<DeleteResult> deleteStream(String stream,
                                                        ExpectedVersion expectedVersion,
                                                        boolean hardDelete,
                                                        UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkNotNull(expectedVersion, "expectedVersion");

        CompletableFuture<DeleteResult> result = new CompletableFuture<>();
        enqueue(new DeleteStreamOperation(result, settings.requireMaster, stream, expectedVersion.value, hardDelete, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<WriteResult> appendToStream(String stream,
                                                         ExpectedVersion expectedVersion,
                                                         Iterable<EventData> events,
                                                         UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkNotNull(expectedVersion, "expectedVersion");
        checkNotNull(events, "events");

        CompletableFuture<WriteResult> result = new CompletableFuture<>();
        enqueue(new AppendToStreamOperation(result, settings.requireMaster, stream, expectedVersion.value, events, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<Transaction> startTransaction(String stream,
                                                           ExpectedVersion expectedVersion,
                                                           UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkNotNull(expectedVersion, "expectedVersion");

        CompletableFuture<Transaction> result = new CompletableFuture<>();
        enqueue(new StartTransactionOperation(result, settings.requireMaster, stream, expectedVersion.value, transactionManager, userCredentials));
        return result;
    }

    @Override
    public Transaction continueTransaction(long transactionId, UserCredentials userCredentials) {
        return new Transaction(transactionId, userCredentials, transactionManager);
    }

    @Override
    public CompletableFuture<EventReadResult> readEvent(String stream,
                                                        int eventNumber,
                                                        boolean resolveLinkTos,
                                                        UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkArgument(eventNumber >= -1, "Event number out of range");

        CompletableFuture<EventReadResult> result = new CompletableFuture<>();
        enqueue(new ReadEventOperation(result, stream, eventNumber, resolveLinkTos, settings.requireMaster, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<StreamEventsSlice> readStreamEventsForward(String stream,
                                                                        int start,
                                                                        int count,
                                                                        boolean resolveLinkTos,
                                                                        UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkArgument(!isNegative(start), "start should not be negative.");
        checkArgument(isPositive(count), "count should be positive.");
        checkArgument(count < MAX_READ_SIZE, "Count should be less than %d. For larger reads you should page.", MAX_READ_SIZE);

        CompletableFuture<StreamEventsSlice> result = new CompletableFuture<>();
        enqueue(new ReadStreamEventsForwardOperation(result, stream, start, count, resolveLinkTos, settings.requireMaster, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<StreamEventsSlice> readStreamEventsBackward(String stream,
                                                                         int start,
                                                                         int count,
                                                                         boolean resolveLinkTos,
                                                                         UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkArgument(isPositive(count), "count should be positive.");
        checkArgument(count < MAX_READ_SIZE, "Count should be less than %d. For larger reads you should page.", MAX_READ_SIZE);

        CompletableFuture<StreamEventsSlice> result = new CompletableFuture<>();
        enqueue(new ReadStreamEventsBackwardOperation(result, stream, start, count, resolveLinkTos, settings.requireMaster, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<AllEventsSlice> readAllEventsForward(Position position,
                                                                  int maxCount,
                                                                  boolean resolveLinkTos,
                                                                  UserCredentials userCredentials) {
        checkArgument(isPositive(maxCount), "count should be positive.");
        checkArgument(maxCount < MAX_READ_SIZE, "Count should be less than %d. For larger reads you should page.", MAX_READ_SIZE);

        CompletableFuture<AllEventsSlice> result = new CompletableFuture<>();
        enqueue(new ReadAllEventsForwardOperation(result, position, maxCount, resolveLinkTos, settings.requireMaster, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<AllEventsSlice> readAllEventsBackward(Position position,
                                                                   int maxCount,
                                                                   boolean resolveLinkTos,
                                                                   UserCredentials userCredentials) {
        checkArgument(isPositive(maxCount), "count should be positive.");
        checkArgument(maxCount < MAX_READ_SIZE, "Count should be less than %d. For larger reads you should page.", MAX_READ_SIZE);

        CompletableFuture<AllEventsSlice> result = new CompletableFuture<>();
        enqueue(new ReadAllEventsBackwardOperation(result, position, maxCount, resolveLinkTos, settings.requireMaster, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<Subscription> subscribeToStream(String stream,
                                                             boolean resolveLinkTos,
                                                             VolatileSubscriptionListener listener,
                                                             UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkNotNull(listener, "listener");

        CompletableFuture<Subscription> result = new CompletableFuture<>();
        enqueue(new StartSubscription(result, stream, resolveLinkTos, userCredentials, listener, settings.maxOperationRetries, settings.operationTimeout));
        return result;
    }

    @Override
    public CompletableFuture<Subscription> subscribeToAll(boolean resolveLinkTos,
                                                          VolatileSubscriptionListener listener,
                                                          UserCredentials userCredentials) {
        checkNotNull(listener, "listener");

        CompletableFuture<Subscription> result = new CompletableFuture<>();
        enqueue(new StartSubscription(result, Strings.EMPTY, resolveLinkTos, userCredentials, listener, settings.maxOperationRetries, settings.operationTimeout));
        return result;
    }

    @Override
    public CatchUpSubscription subscribeToStreamFrom(String stream,
                                                     Integer fromEventNumberExclusive,
                                                     CatchUpSubscriptionSettings settings,
                                                     CatchUpSubscriptionListener listener,
                                                     UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkNotNull(listener, "listener");
        checkNotNull(settings, "settings");

        CatchUpSubscription subscription = new StreamCatchUpSubscription(this,
            stream, fromEventNumberExclusive, settings.resolveLinkTos, listener, userCredentials, settings.readBatchSize, settings.maxLiveQueueSize, executor());

        subscription.start();

        return subscription;
    }

    @Override
    public CatchUpSubscription subscribeToAllFrom(Position fromPositionExclusive,
                                                  CatchUpSubscriptionSettings settings,
                                                  CatchUpSubscriptionListener listener,
                                                  UserCredentials userCredentials) {
        checkNotNull(listener, "listener");
        checkNotNull(settings, "settings");

        CatchUpSubscription subscription = new AllCatchUpSubscription(this,
            fromPositionExclusive, settings.resolveLinkTos, listener, userCredentials, settings.readBatchSize, settings.maxLiveQueueSize, executor());

        subscription.start();

        return subscription;
    }

    @Override
    public CompletableFuture<PersistentSubscription> subscribeToPersistent(String stream,
                                                                           String groupName,
                                                                           PersistentSubscriptionListener listener,
                                                                           UserCredentials userCredentials,
                                                                           int bufferSize,
                                                                           boolean autoAck) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkArgument(!isNullOrEmpty(groupName), "groupName");
        checkNotNull(listener, "listener");
        checkArgument(isPositive(bufferSize), "bufferSize should be positive");

        PersistentSubscription subscription = new PersistentSubscription(groupName, stream, listener, userCredentials, bufferSize, autoAck, executor()) {
            @Override
            protected CompletableFuture<Subscription> startSubscription(String subscriptionId,
                                                                        String streamId,
                                                                        int bufferSize,
                                                                        SubscriptionListener<PersistentSubscriptionChannel> listener,
                                                                        UserCredentials userCredentials) {
                CompletableFuture<Subscription> result = new CompletableFuture<>();
                enqueue(new StartPersistentSubscription(result, subscriptionId, streamId, bufferSize,
                    userCredentials, listener, settings.maxOperationRetries, settings.operationTimeout));
                return result;
            }
        };

        return subscription.start();
    }

    @Override
    public CompletableFuture<PersistentSubscriptionCreateResult> createPersistentSubscription(String stream,
                                                                                              String groupName,
                                                                                              PersistentSubscriptionSettings settings,
                                                                                              UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkArgument(!isNullOrEmpty(groupName), "groupName");
        checkNotNull(settings, "settings");

        CompletableFuture<PersistentSubscriptionCreateResult> result = new CompletableFuture<>();
        enqueue(new CreatePersistentSubscriptionOperation(result, stream, groupName, settings, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<PersistentSubscriptionUpdateResult> updatePersistentSubscription(String stream,
                                                                                              String groupName,
                                                                                              PersistentSubscriptionSettings settings,
                                                                                              UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkArgument(!isNullOrEmpty(groupName), "groupName");
        checkNotNull(settings, "settings");

        CompletableFuture<PersistentSubscriptionUpdateResult> result = new CompletableFuture<>();
        enqueue(new UpdatePersistentSubscriptionOperation(result, stream, groupName, settings, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<PersistentSubscriptionDeleteResult> deletePersistentSubscription(String stream,
                                                                                              String groupName,
                                                                                              UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkArgument(!isNullOrEmpty(groupName), "groupName");

        CompletableFuture<PersistentSubscriptionDeleteResult> result = new CompletableFuture<>();
        enqueue(new DeletePersistentSubscriptionOperation(result, stream, groupName, userCredentials));
        return result;
    }

    @Override
    public CompletableFuture<WriteResult> setStreamMetadata(String stream,
                                                            ExpectedVersion expectedMetastreamVersion,
                                                            byte[] metadata,
                                                            UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");
        checkArgument(!isMetastream(stream), "Setting metadata for metastream '%s' is not supported.", stream);
        checkNotNull(expectedMetastreamVersion, "expectedMetastreamVersion");

        CompletableFuture<WriteResult> result = new CompletableFuture<>();

        EventData metaevent = EventData.newBuilder()
            .type(SystemEventType.STREAM_METADATA.value)
            .jsonData(metadata)
            .build();

        enqueue(new AppendToStreamOperation(result, settings.requireMaster, SystemStreams.metastreamOf(stream),
            expectedMetastreamVersion.value, singletonList(metaevent), userCredentials));

        return result;
    }

    @Override
    public CompletableFuture<StreamMetadataResult> getStreamMetadata(String stream, UserCredentials userCredentials) {
        CompletableFuture<StreamMetadataResult> result = new CompletableFuture<>();

        getStreamMetadataAsRawBytes(stream, userCredentials).whenComplete((r, t) -> {
            if (t != null) {
                result.completeExceptionally(t);
            } else if (r.streamMetadata == null || r.streamMetadata.length == 0) {
                result.complete(new StreamMetadataResult(r.stream, r.isStreamDeleted, r.metastreamVersion, StreamMetadata.empty()));
            } else {
                try {
                    result.complete(new StreamMetadataResult(r.stream, r.isStreamDeleted, r.metastreamVersion, StreamMetadata.fromJson(r.streamMetadata)));
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            }
        });

        return result;
    }

    @Override
    public CompletableFuture<RawStreamMetadataResult> getStreamMetadataAsRawBytes(String stream, UserCredentials userCredentials) {
        checkArgument(!isNullOrEmpty(stream), "stream");

        CompletableFuture<RawStreamMetadataResult> result = new CompletableFuture<>();

        readEvent(SystemStreams.metastreamOf(stream), StreamPosition.END, false, userCredentials).whenComplete((r, t) -> {
            if (t != null) {
                result.completeExceptionally(t);
            } else {
                switch (r.status) {
                    case Success:
                        if (r.event == null) {
                            result.completeExceptionally(new Exception("Event is null while operation result is Success."));
                        } else {
                            RecordedEvent event = r.event.originalEvent();
                            result.complete((event == null) ?
                                new RawStreamMetadataResult(stream, false, -1, EMPTY_BYTES) :
                                new RawStreamMetadataResult(stream, false, event.eventNumber, event.data));
                        }
                        break;
                    case NotFound:
                    case NoStream:
                        result.complete(new RawStreamMetadataResult(stream, false, -1, EMPTY_BYTES));
                        break;
                    case StreamDeleted:
                        result.complete(new RawStreamMetadataResult(stream, true, Integer.MAX_VALUE, EMPTY_BYTES));
                        break;
                    default:
                        result.completeExceptionally(new IllegalStateException("Unexpected ReadEventResult: " + r.status));
                }
            }
        });

        return result;
    }

    @Override
    public CompletableFuture<WriteResult> setSystemSettings(SystemSettings settings, UserCredentials userCredentials) {
        checkNotNull(settings, "settings");
        return appendToStream(SystemStreams.SETTINGS_STREAM,
            ExpectedVersion.any(),
            singletonList(EventData.newBuilder()
                .type(SystemEventType.SETTINGS.value)
                .jsonData(settings.toJson())
                .build()),
            userCredentials);
    }

    @Override
    public void connect() {
        if (!isRunning()) {
            timer = group.scheduleAtFixedRate(this::timerTick, 200, 200, MILLISECONDS);
            reconnectionInfo.reset();
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        result.whenComplete((value, throwable) -> {
            if (throwable != null) {
                logger.error("Unable to connect: {}", throwable.getMessage());
            }
        });
        tasks.enqueue(new StartConnection(result, discoverer));
    }

    @Override
    public void disconnect() {
        disconnect("exit");
    }

    private void disconnect(String reason) {
        if (isRunning()) {
            timer.cancel(true);
            timer = null;
            operationManager.cleanUp();
            subscriptionManager.cleanUp();
            closeTcpConnection(reason);
            connectingPhase = ConnectingPhase.INVALID;
            fireEvent(Events.clientDisconnected());
            logger.info("Disconnected, reason: {}", reason);
        }
    }

    @Override
    public boolean isRunning() {
        return timer != null && !timer.isDone();
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public void addListener(EventStoreListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(EventStoreListener listener) {
        listeners.remove(listener);
    }

    private Executor executor() {
        return settings.executor;
    }

    private void fireEvent(Event event) {
        executor().execute(() -> listeners.forEach(l -> l.onEvent(event)));
    }

    private void onAuthenticationCompleted(AuthenticationStatus status) {
        if (status == AuthenticationStatus.SUCCESS || status == AuthenticationStatus.IGNORED) {
            gotoConnectedPhase();
        } else {
            fireEvent(Events.authenticationFailed());
        }
    }

    private void onBadRequest(TcpPackage tcpPackage) {
        handle(new CloseConnection("Connection-wide BadRequest received. Too dangerous to continue.",
            new EventStoreException("Bad request received from server. Error: " + defaultIfEmpty(newString(tcpPackage.data), "<no message>"))));
    }

    private void onChannelError(Throwable throwable) {
        fireEvent(Events.errorOccurred(throwable));
    }

    private void onReconnect(NodeEndpoints nodeEndpoints) {
        reconnectTo(nodeEndpoints);
    }

    private void timerTick() {
        switch (connectionState()) {
            case INIT:
                if (connectingPhase == ConnectingPhase.RECONNECTING && between(reconnectionInfo.timestamp, now()).compareTo(settings.reconnectionDelay) > 0) {
                    logger.debug("Checking reconnection...");

                    reconnectionInfo.inc();

                    if (settings.maxReconnections >= 0 && reconnectionInfo.reconnectionAttempt > settings.maxReconnections) {
                        handle(new CloseConnection("Reconnection limit reached"));
                    } else {
                        fireEvent(Events.clientReconnecting());
                        discoverEndpoint(Optional.empty());
                    }
                }
                break;
            case CONNECTED:
                checkOperationTimeout();
                break;
        }
    }

    private void checkOperationTimeout() {
        if (between(lastOperationTimeoutCheck, now()).compareTo(settings.operationTimeoutCheckInterval) > 0) {
            operationManager.checkTimeoutsAndRetry(connection);
            subscriptionManager.checkTimeoutsAndRetry(connection);
            lastOperationTimeoutCheck = now();
        }
    }

    private void gotoConnectedPhase() {
        checkNotNull(connection, "connection");
        connectingPhase = ConnectingPhase.CONNECTED;
        reconnectionInfo.reset();
        fireEvent(Events.clientConnected((InetSocketAddress) connection.remoteAddress()));
        checkOperationTimeout();
    }

    private void reconnectTo(NodeEndpoints endpoints) {
        InetSocketAddress endpoint = (settings.sslSettings.useSslConnection && endpoints.secureTcpEndpoint != null) ?
            endpoints.secureTcpEndpoint : endpoints.tcpEndpoint;

        if (endpoint == null) {
            handle(new CloseConnection("No endpoint is specified while trying to reconnect."));
        } else if (connectionState() == ConnectionState.CONNECTED && !connection.remoteAddress().equals(endpoint)) {
            String message = String.format("Connection '%s': going to reconnect to [%s]. Current endpoint: [%s, L%s].",
                ChannelId.of(connection), endpoint, connection.remoteAddress(), connection.localAddress());

            logger.trace(message);

            closeTcpConnection(message);

            connectingPhase = ConnectingPhase.ENDPOINT_DISCOVERY;
            handle(new EstablishTcpConnection(endpoints));
        }
    }

    private void discoverEndpoint(Optional<CompletableFuture<Void>> result) {
        logger.debug("Discovering endpoint...");

        if (connectionState() == ConnectionState.INIT && connectingPhase == ConnectingPhase.RECONNECTING) {
            connectingPhase = ConnectingPhase.ENDPOINT_DISCOVERY;

            discoverer.discover(connection != null ? (InetSocketAddress) connection.remoteAddress() : null)
                .whenComplete((nodeEndpoints, throwable) -> {
                    if (throwable == null) {
                        tasks.enqueue(new EstablishTcpConnection(nodeEndpoints));
                        result.ifPresent(r -> r.complete(null));
                    } else {
                        tasks.enqueue(new CloseConnection("Failed to resolve TCP endpoint to which to connect.", throwable));
                        result.ifPresent(r -> r.completeExceptionally(new CannotEstablishConnectionException("Cannot resolve target end point.", throwable)));
                    }
                });
        }
    }

    private void closeTcpConnection(String reason) {
        if (connection != null) {
            logger.debug("Closing TCP connection, reason: {}", reason);
            try {
                connection.close().await(settings.tcpSettings.closeTimeout.toMillis());
            } catch (Exception e) {
                logger.warn("Unable to close connection gracefully", e);
            }
        } else {
            onTcpConnectionClosed();
        }
    }

    private void onTcpConnectionClosed() {
        if (connection != null) {
            subscriptionManager.purgeSubscribedAndDropped(ChannelId.of(connection));
            fireEvent(Events.connectionClosed());
        }

        connection = null;
        connectingPhase = ConnectingPhase.RECONNECTING;
        reconnectionInfo.touch();
    }

    private void handle(StartConnection task) {
        logger.debug("StartConnection");

        switch (connectionState()) {
            case INIT:
                connectingPhase = ConnectingPhase.RECONNECTING;
                discoverEndpoint(Optional.of(task.result));
                break;
            case CONNECTING:
            case CONNECTED:
                task.result.completeExceptionally(new IllegalStateException(String.format("Connection %s is already active.", connection)));
                break;
            case CLOSED:
                task.result.completeExceptionally(new ConnectionClosedException("Connection is closed"));
                break;
            default:
                throw new IllegalStateException("Unknown connection state");
        }
    }

    private void handle(EstablishTcpConnection task) {
        InetSocketAddress endpoint = (settings.sslSettings.useSslConnection && task.endpoints.secureTcpEndpoint != null) ?
            task.endpoints.secureTcpEndpoint : task.endpoints.tcpEndpoint;

        if (endpoint == null) {
            handle(new CloseConnection("No endpoint to node specified."));
        } else {
            logger.debug("Connecting to [{}]...", endpoint);

            if (connectionState() == ConnectionState.INIT && connectingPhase == ConnectingPhase.ENDPOINT_DISCOVERY) {
                connectingPhase = ConnectingPhase.CONNECTION_ESTABLISHING;

                bootstrap.connect(endpoint).addListener((ChannelFuture connectFuture) -> {
                    if (connectFuture.isSuccess()) {
                        logger.info("Connection to [{}, L{}] established.", connectFuture.channel().remoteAddress(), connectFuture.channel().localAddress());

                        connectingPhase = ConnectingPhase.AUTHENTICATION;

                        connection = connectFuture.channel();

                        connection.closeFuture().addListener((ChannelFuture closeFuture) -> {
                            logger.info("Connection to [{}, L{}] closed.", closeFuture.channel().remoteAddress(), closeFuture.channel().localAddress());
                            onTcpConnectionClosed();
                        });
                    } else {
                        closeTcpConnection("unable to connect");
                    }
                });
            }
        }
    }

    private void handle(CloseConnection task) {
        if (connectionState() == ConnectionState.CLOSED) {
            logger.debug("CloseConnection IGNORED because connection is CLOSED, reason: " + task.reason, task.throwable);
        } else {
            logger.debug("CloseConnection, reason: " + task.reason, task.throwable);

            if (task.throwable != null) {
                fireEvent(Events.errorOccurred(task.throwable));
            }

            disconnect(task.reason);
        }
    }

    private void handle(StartOperation task) {
        Operation operation = task.operation;

        switch (connectionState()) {
            case INIT:
                if (connectingPhase == ConnectingPhase.INVALID) {
                    operation.fail(new IllegalStateException("No connection"));
                    break;
                }
            case CONNECTING:
                logger.debug("StartOperation enqueue {}, {}, {}, {}.", operation.getClass().getSimpleName(), operation, settings.maxOperationRetries, settings.operationTimeout);
                operationManager.enqueueOperation(new OperationItem(operation, settings.maxOperationRetries, settings.operationTimeout));
                break;
            case CONNECTED:
                logger.debug("StartOperation schedule {}, {}, {}, {}.", operation.getClass().getSimpleName(), operation, settings.maxOperationRetries, settings.operationTimeout);
                operationManager.scheduleOperation(new OperationItem(operation, settings.maxOperationRetries, settings.operationTimeout), connection);
                break;
            case CLOSED:
                operation.fail(new ConnectionClosedException("Connection is closed"));
                break;
            default:
                throw new IllegalStateException("Unknown connection state");
        }
    }

    private void handle(StartSubscription task) {
        ConnectionState state = connectionState();

        switch (state) {
            case INIT:
                if (connectingPhase == ConnectingPhase.INVALID) {
                    task.result.completeExceptionally(new IllegalStateException("No connection"));
                    break;
                }
            case CONNECTING:
            case CONNECTED:
                VolatileSubscriptionOperation operation = new VolatileSubscriptionOperation(
                    task.result,
                    task.streamId, task.resolveLinkTos, task.userCredentials, task.listener,
                    () -> connection, executor());

                logger.debug("StartSubscription {} {}, {}, {}, {}.",
                    state == ConnectionState.CONNECTED ? "fire" : "enqueue",
                    operation.getClass().getSimpleName(), operation, task.maxRetries, task.timeout);

                SubscriptionItem item = new SubscriptionItem(operation, task.maxRetries, task.timeout);

                if (state == ConnectionState.CONNECTED) {
                    subscriptionManager.startSubscription(item, connection);
                } else {
                    subscriptionManager.enqueueSubscription(item);
                }
                break;
            case CLOSED:
                task.result.completeExceptionally(new ConnectionClosedException("Connection is closed"));
                break;
            default:
                throw new IllegalStateException("Unknown connection state");
        }
    }

    private void handle(StartPersistentSubscription task) {
        ConnectionState state = connectionState();

        switch (state) {
            case INIT:
                if (connectingPhase == ConnectingPhase.INVALID) {
                    task.result.completeExceptionally(new IllegalStateException("No connection"));
                    break;
                }
            case CONNECTING:
            case CONNECTED:
                PersistentSubscriptionOperation operation = new PersistentSubscriptionOperation(
                    task.result,
                    task.subscriptionId, task.streamId, task.bufferSize, task.userCredentials, task.listener,
                    () -> connection, executor());

                logger.debug("StartSubscription {} {}, {}, {}, {}.",
                    state == ConnectionState.CONNECTED ? "fire" : "enqueue",
                    operation.getClass().getSimpleName(), operation, task.maxRetries, task.timeout);

                SubscriptionItem item = new SubscriptionItem(operation, task.maxRetries, task.timeout);

                if (state == ConnectionState.CONNECTED) {
                    subscriptionManager.startSubscription(item, connection);
                } else {
                    subscriptionManager.enqueueSubscription(item);
                }
                break;
            case CLOSED:
                task.result.completeExceptionally(new ConnectionClosedException("Connection is closed"));
                break;
            default:
                throw new IllegalStateException("Unknown connection state");
        }
    }

    private void enqueue(Operation operation) {
        while (operationManager.totalOperationCount() >= settings.maxOperationQueueSize) {
            sleepUninterruptibly(1);
        }
        enqueue(new StartOperation(operation));
    }

    private void enqueue(Task task) {
        synchronized (mutex) {
            if (!isRunning()) {
                connect();
            }
        }
        logger.trace("enqueueing task {}.", task.getClass().getSimpleName());
        tasks.enqueue(task);
    }

    private ConnectionState connectionState() {
        if (connection == null) {
            return ConnectionState.INIT;
        } else if (connection.isOpen()) {
            return (connection.isActive() && (connectingPhase == ConnectingPhase.CONNECTED)) ?
                ConnectionState.CONNECTED : ConnectionState.CONNECTING;
        } else {
            return ConnectionState.CLOSED;
        }
    }

    private class TransactionManagerImpl implements TransactionManager {

        @Override
        public CompletableFuture<Void> write(Transaction transaction, Iterable<EventData> events, UserCredentials userCredentials) {
            checkNotNull(transaction, "transaction");
            checkNotNull(events, "events");

            CompletableFuture<Void> result = new CompletableFuture<>();
            enqueue(new TransactionalWriteOperation(result, settings.requireMaster, transaction.transactionId, events, userCredentials));
            return result;
        }

        @Override
        public CompletableFuture<WriteResult> commit(Transaction transaction, UserCredentials userCredentials) {
            checkNotNull(transaction, "transaction");

            CompletableFuture<WriteResult> result = new CompletableFuture<>();
            enqueue(new CommitTransactionOperation(result, settings.requireMaster, transaction.transactionId, userCredentials));
            return result;
        }
    }

    private static class ReconnectionInfo {
        int reconnectionAttempt;
        Instant timestamp;

        void inc() {
            reconnectionAttempt++;
            touch();
        }

        void reset() {
            reconnectionAttempt = 0;
            touch();
        }

        void touch() {
            timestamp = now();
        }
    }
}
