/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.node.service.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.atomix.node.service.OperationExecutor;
import io.atomix.node.service.ServiceException;
import io.atomix.node.service.ServiceOperationRegistry;
import io.atomix.node.service.operation.OperationId;
import io.atomix.node.service.operation.StreamType;
import io.atomix.node.service.util.ByteArrayDecoder;
import io.atomix.node.service.util.ByteArrayEncoder;
import io.atomix.utils.stream.EncodingStreamHandler;
import io.atomix.utils.stream.StreamHandler;
import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

public class DefaultServiceExecutor implements ServiceOperationRegistry {
    private final Logger logger;
    private final ServiceCodec codec;
    private final Map<OperationId, StreamType> streamTypes = new HashMap<>();
    private final Map<OperationId, OperationExecutor> operations = new HashMap<>();
    private OperationId operationId;

    public DefaultServiceExecutor(ServiceCodec codec, Logger logger) {
        this.codec = codec;
        this.logger = logger;
    }

    /**
     * Returns the stream type for the given operation.
     *
     * @param operationId the operation ID
     * @param <T>         the stream type
     * @return the stream type for the given operation
     */
    public <T> StreamType<T> getStreamType(OperationId<?, T> operationId) {
        return streamTypes.get(operationId);
    }

    /**
     * Returns the executor for the given operation.
     *
     * @param operationId the operation ID
     * @param <T>         the request type
     * @param <U>         the response type
     * @return the operation executor
     */
    @SuppressWarnings("unchecked")
    public <T, U> OperationExecutor<T, U> getExecutor(OperationId<T, U> operationId) {
        return operations.get(operationId);
    }

    @Override
    public void register(OperationId<Void, Void> operationId, Runnable callback) {
        checkNotNull(operationId, "operationId cannot be null");
        checkNotNull(callback, "callback cannot be null");
        operations.put(operationId, new UnaryOperationExecutor<>(
            operationId,
            request -> {
                callback.run();
                return null;
            },
            codec.register(operationId)));
    }

    @Override
    public <R> void register(OperationId<Void, R> operationId, Supplier<R> callback, ByteArrayEncoder<R> encoder) {
        checkNotNull(operationId, "operationId cannot be null");
        checkNotNull(callback, "callback cannot be null");
        operations.put(operationId, new UnaryOperationExecutor<>(
            operationId,
            request -> callback.get(),
            codec.register(operationId, encoder)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void register(OperationId<T, Void> operationId, Consumer<T> callback, ByteArrayDecoder<T> decoder) {
        checkNotNull(operationId, "operationId cannot be null");
        checkNotNull(callback, "callback cannot be null");
        operations.put(operationId, new UnaryOperationExecutor<>(
            operationId,
            request -> {
                callback.accept(request);
                return null;
            },
            codec.register(operationId, decoder)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, R> void register(
        OperationId<T, R> operationId,
        Function<T, R> callback,
        ByteArrayDecoder<T> decoder,
        ByteArrayEncoder<R> encoder) {
        checkNotNull(operationId, "operationId cannot be null");
        checkNotNull(callback, "callback cannot be null");
        operations.put(operationId, new UnaryOperationExecutor<>(
            operationId,
            callback,
            codec.register(operationId, decoder, encoder)));
    }

    @Override
    public <T, R> void register(
        OperationId<T, R> operationId,
        StreamType<R> streamType,
        Function<T, CompletableFuture<R>> callback,
        ByteArrayDecoder<T> decoder,
        ByteArrayEncoder<R> encoder) {
        checkNotNull(operationId, "operationId cannot be null");
        checkNotNull(streamType, "streamType cannot be null");
        checkNotNull(callback, "callback cannot be null");
        streamTypes.put(operationId, streamType);
        codec.register(streamType, encoder);
        operations.put(operationId, new AsyncOperationExecutor<>(
            operationId,
            callback,
            codec.register(operationId, decoder, encoder)));
        logger.trace("Registered streaming operation callback {}", operationId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> void register(
        OperationId<Void, R> operationId,
        StreamType<R> streamType,
        Consumer<StreamHandler<R>> callback,
        ByteArrayEncoder<R> encoder) {
        checkNotNull(operationId, "operationId cannot be null");
        checkNotNull(streamType, "streamType cannot be null");
        checkNotNull(callback, "callback cannot be null");
        codec.register(streamType, encoder);
        streamTypes.put(operationId, streamType);
        operations.put(operationId, new StreamOperationExecutor<>(
            operationId,
            (request, handler) -> callback.accept(handler),
            codec.register(operationId, encoder)));
        logger.trace("Registered streaming operation callback {}", operationId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, R> void register(
        OperationId<T, R> operationId,
        StreamType<R> streamType,
        BiConsumer<T, StreamHandler<R>> callback,
        ByteArrayDecoder<T> decoder,
        ByteArrayEncoder<R> encoder) {
        checkNotNull(operationId, "operationId cannot be null");
        checkNotNull(streamType, "streamType cannot be null");
        checkNotNull(callback, "callback cannot be null");
        streamTypes.put(operationId, streamType);
        codec.register(streamType, encoder);
        operations.put(operationId, new StreamOperationExecutor<>(
            operationId,
            callback,
            codec.register(operationId, decoder, encoder)));
        logger.trace("Registered streaming operation callback {}", operationId);
    }

    private class UnaryOperationExecutor<T, U> implements OperationExecutor<T, U> {
        private final OperationId<T, U> operationId;
        private final Function<T, U> function;
        private final OperationCodec<T, U> operationCodec;

        UnaryOperationExecutor(
            OperationId<T, U> operationId,
            Function<T, U> function,
            OperationCodec<T, U> operationCodec) {
            this.operationId = operationId;
            this.function = function;
            this.operationCodec = operationCodec;
        }

        @Override
        public byte[] execute(byte[] request) {
            return operationCodec.encode(execute(operationCodec.decode(request)));
        }

        @Override
        public void execute(byte[] request, StreamHandler<byte[]> handler) {
            execute(operationCodec.decode(request), new EncodingStreamHandler<>(handler, operationCodec::encode));
        }

        @Override
        public U execute(T request) {
            logger.trace("Executing {}", request);
            try {
                return function.apply(request);
            } catch (Exception e) {
                logger.warn("State machine operation failed: {}", e.getMessage());
                throw new ServiceException.ApplicationException(e);
            }
        }

        @Override
        public void execute(T request, StreamHandler<U> handler) {
            logger.trace("Executing {}", operationId);
            try {
                function.apply(request);
                handler.complete();
            } catch (Exception e) {
                logger.warn("State machine operation failed: {}", e.getMessage());
                handler.error(e);
            }
        }
    }

    private class AsyncOperationExecutor<T, U> implements OperationExecutor<T, U> {
        private final OperationId<T, U> operationId;
        private final Function<T, CompletableFuture<U>> function;
        private final OperationCodec<T, U> operationCodec;

        AsyncOperationExecutor(
            OperationId<T, U> operationId,
            Function<T, CompletableFuture<U>> function,
            OperationCodec<T, U> operationCodec) {
            this.operationId = operationId;
            this.function = function;
            this.operationCodec = operationCodec;
        }

        @Override
        public byte[] execute(byte[] request) {
            return operationCodec.encode(execute(operationCodec.decode(request)));
        }

        @Override
        public void execute(byte[] request, StreamHandler<byte[]> handler) {
            execute(operationCodec.decode(request), new EncodingStreamHandler<>(handler, operationCodec::encode));
        }

        @Override
        public U execute(T request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(T request, StreamHandler<U> handler) {
            logger.trace("Executing {}", operationId);
            try {
                function.apply(request).whenComplete((response, error) -> {
                    if (error == null) {
                        handler.next(response);
                        handler.complete();
                    } else {
                        handler.error(error);
                    }
                });
            } catch (Exception e) {
                logger.warn("State machine operation failed: {}", e.getMessage());
                throw new ServiceException.ApplicationException(e);
            }
        }
    }

    private class StreamOperationExecutor<T, U> implements OperationExecutor<T, U> {
        private final OperationId<T, U> operationId;
        private final BiConsumer<T, StreamHandler<U>> function;
        private final OperationCodec<T, U> operationCodec;

        StreamOperationExecutor(
            OperationId<T, U> operationId,
            BiConsumer<T, StreamHandler<U>> function,
            OperationCodec<T, U> operationCodec) {
            this.operationId = operationId;
            this.function = function;
            this.operationCodec = operationCodec;
        }

        @Override
        public byte[] execute(byte[] request) {
            return operationCodec.encode(execute(operationCodec.decode(request)));
        }

        @Override
        public void execute(byte[] request, StreamHandler<byte[]> handler) {
            execute(operationCodec.decode(request), new EncodingStreamHandler<>(handler, operationCodec::encode));
        }

        @Override
        public U execute(T request) {
            execute(request, new StreamHandler<U>() {
                @Override
                public void next(U value) {

                }

                @Override
                public void complete() {

                }

                @Override
                public void error(Throwable error) {

                }
            });
            return null;
        }

        @Override
        public void execute(T request, StreamHandler<U> handler) {
            logger.trace("Executing {}", operationId);
            try {
                function.accept(request, handler);
            } catch (Exception e) {
                logger.warn("State machine operation failed: {}", e.getMessage());
                throw new ServiceException.ApplicationException(e);
            }
        }
    }
}