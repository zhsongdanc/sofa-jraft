/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft.rpc.impl;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.Url;
import com.alipay.remoting.exception.RemotingException;
import com.alipay.remoting.rpc.RpcClient;
import com.alipay.remoting.rpc.exception.InvokeTimeoutException;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.option.RpcOptions;
import com.alipay.sofa.jraft.rpc.ClientService;
import com.alipay.sofa.jraft.rpc.ProtobufMsgFactory;
import com.alipay.sofa.jraft.rpc.RpcRequests.ErrorResponse;
import com.alipay.sofa.jraft.rpc.RpcRequests.PingRequest;
import com.alipay.sofa.jraft.rpc.RpcResponseClosure;
import com.alipay.sofa.jraft.rpc.impl.core.BoltRaftClientService;
import com.alipay.sofa.jraft.rpc.impl.core.JraftRpcAddressParser;
import com.alipay.sofa.jraft.util.Endpoint;
import com.alipay.sofa.jraft.util.NamedThreadFactory;
import com.alipay.sofa.jraft.util.ThreadPoolMetricSet;
import com.alipay.sofa.jraft.util.ThreadPoolUtil;
import com.alipay.sofa.jraft.util.Utils;
import com.google.protobuf.Message;

/**
 * Abstract RPC client service based on bolt.

 * @author boyan (boyan@alibaba-inc.com)
 *
 * 2018-Apr-09 3:27:33 PM
 */
public abstract class AbstractBoltClientService implements ClientService {

    protected static final Logger   LOG = LoggerFactory.getLogger(BoltRaftClientService.class);

    static {
        ProtobufMsgFactory.load();
    }

    protected RpcClient             rpcClient;
    protected ThreadPoolExecutor    rpcExecutor;
    protected RpcOptions            rpcOptions;
    protected JraftRpcAddressParser rpcAddressParser;

    public RpcClient getRpcClient() {
        return this.rpcClient;
    }

    @Override
    public boolean isConnected(Endpoint endpoint) {
        return this.rpcClient.checkConnection(endpoint.toString());
    }

    @Override
    public synchronized boolean init(RpcOptions rpcOptions) {
        if (this.rpcClient != null) {
            return true;
        }
        this.rpcOptions = rpcOptions;
        final int rpcProcessorThreadPoolSize = this.rpcOptions.getRpcProcessorThreadPoolSize();
        this.rpcAddressParser = new JraftRpcAddressParser();
        return initRpcClient(rpcProcessorThreadPoolSize);
    }

    protected void configRpcClient(RpcClient rpcClient) {
        // NO-OP
    }

    protected boolean initRpcClient(int rpcProcessorThreadPoolSize) {
        this.rpcClient = new RpcClient();
        this.configRpcClient(rpcClient);
        this.rpcClient.init();
        // todo threadpool
        this.rpcExecutor = ThreadPoolUtil.newThreadPool("JRaft-RPC-Processor", true, rpcProcessorThreadPoolSize / 3,
            rpcProcessorThreadPoolSize, 60L, new ArrayBlockingQueue<>(10000), new NamedThreadFactory(
                "JRaft-RPC-Processor-"));
        // todo 忽略
        if (this.rpcOptions.getMetricRegistry() != null) {
            this.rpcOptions.getMetricRegistry().register("raft-rpc-client-thread-pool",
                new ThreadPoolMetricSet(this.rpcExecutor));
            Utils.registerClosureExecutorMetrics(this.rpcOptions.getMetricRegistry());
        }
        return true;
    }

    @Override
    public synchronized void shutdown() {
        if (this.rpcClient != null) {
            this.rpcClient.shutdown();
            this.rpcClient = null;
            this.rpcExecutor.shutdown();
        }
    }

    @Override
    public boolean connect(Endpoint endpoint) {
        if (this.rpcClient == null) {
            throw new IllegalStateException("Client service is not inited.");
        }
        if (this.isConnected(endpoint)) {
            return true;
        }
        try {
            final PingRequest req = PingRequest.newBuilder().setSendTimestamp(System.currentTimeMillis()).build();
            final ErrorResponse resp = (ErrorResponse) rpcClient.invokeSync(endpoint.toString(), req,
                rpcOptions.getRpcConnectTimeoutMs());
            return resp.getErrorCode() == 0;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final RemotingException e) {
            LOG.error("Fail to connect {}, remoting exception: {}", endpoint, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean disconnect(Endpoint endpoint) {
        LOG.info("Disconnect from {}", endpoint);
        this.rpcClient.closeConnection(endpoint.toString());
        return true;
    }

    public <T extends Message> Future<Message> invokeWithDone(Endpoint endpoint, Message request,
                                                              RpcResponseClosure<T> done, int timeoutMs) {
        final FutureImpl<Message> future = new FutureImpl<>();
        try {
            final Url rpcUrl = this.rpcAddressParser.parse(endpoint.toString());
            this.rpcClient.invokeWithCallback(rpcUrl, request, new InvokeCallback() {

                @SuppressWarnings("unchecked")
                @Override
                public void onResponse(Object result) {
                    if (future.isCancelled()) {
                        return;
                    }
                    Status status = Status.OK();
                    if (result instanceof ErrorResponse) {
                        final ErrorResponse eResp = (ErrorResponse) result;
                        status = new Status();
                        status.setCode(eResp.getErrorCode());
                        if (eResp.hasErrorMsg()) {
                            status.setErrorMsg(eResp.getErrorMsg());
                        }
                    } else {
                        if (done != null) {
                            done.setResponse((T) result);
                        }
                    }
                    if (done != null) {
                        try {
                            done.run(status);
                        } catch (final Throwable t) {
                            LOG.error("Fail to run RpcResponseClosure, the request is {}", request, t);
                        }
                    }
                    if (!future.isDone()) {
                        future.setResult((Message) result);
                    }
                }

                @Override
                public void onException(Throwable e) {
                    if (future.isCancelled()) {
                        return;
                    }
                    if (done != null) {
                        try {
                            done.run(new Status(e instanceof InvokeTimeoutException ? RaftError.ETIMEDOUT
                                : RaftError.EINTERNAL, "RPC exception:" + e.getMessage()));
                        } catch (final Throwable t) {
                            LOG.error("Fail to run RpcResponseClosure, the request is {}", request, t);
                        }
                    }
                    if (!future.isDone()) {
                        future.failure(e);
                    }
                }

                @Override
                public Executor getExecutor() {
                    return rpcExecutor;
                }
            }, timeoutMs <= 0 ? this.rpcOptions.getRpcDefaultTimeout() : timeoutMs);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            future.failure(e);
            // should be in another thread to avoid dead locking.
            Utils.runClosureInThread(done, new Status(RaftError.EINTR, "Sending rpc was interrupted"));
        } catch (final RemotingException e) {
            future.failure(e);
            // should be in another thread to avoid dead locking.
            Utils.runClosureInThread(done,
                new Status(RaftError.EINTERNAL, "Fail to send a RPC request:" + e.getMessage()));

        }
        return future;
    }
}
