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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.service.GenericService;

import java.lang.reflect.Method;


/**
 * ExceptionInvokerFilter
 * <p>
 * Functions:
 * <ol>
 * <li>unexpected exception will be logged in ERROR level on provider side. Unexpected exception are unchecked
 * exception not declared on the interface</li>
 * <li>Wrap the exception not introduced in API package into RuntimeException. Framework will serialize the outer exception but stringnize its cause in order to avoid of possible serialization problem on client side</li>
 * </ol>
 * 
 * 异常过滤器 ExceptionFilter ，用于服务提供者中。用途如下：
 * 1、不期望的异常打 ERROR 日志( Provider端 )。不期望的日志即是，没有的接口上声明的Unchecked异常。
2、异常不在 API 包中，则 Wrap 一层 RuntimeException 。RPC 对于第一层异常会直接序列化传输( Cause 异常会 String 化) ，避免异常在 Client 出不能反序列化问题。
 * 
 */
@Activate(group = Constants.PROVIDER)
public class ExceptionFilter implements Filter {

    private final Logger logger;

    public ExceptionFilter() {
        this(LoggerFactory.getLogger(ExceptionFilter.class));
    }

    public ExceptionFilter(Logger logger) {
        this.logger = logger;
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        try {
            return invoker.invoke(invocation);
        } catch (RuntimeException e) {
            logger.error("Got unchecked and undeclared exception which called by " + RpcContext.getContext().getRemoteHost()
                    + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName()
                    + ", exception: " + e.getClass().getName() + ": " + e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public Result onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
    	//有异常，并且非泛化调用
        if (result.hasException() && GenericService.class != invoker.getInterface()) {
            try {
                Throwable exception = result.getException();

                // directly throw if it's checked exception
                //// 如果是checked异常，直接抛出
                if (!(exception instanceof RuntimeException) && (exception instanceof Exception)) {
                    return result;
                }
                // directly throw if the exception appears in the signature
                try {
                	// 在方法签名上有声明，直接抛出
                    Method method = invoker.getInterface().getMethod(invocation.getMethodName(), invocation.getParameterTypes());
                    Class<?>[] exceptionClassses = method.getExceptionTypes();
                    for (Class<?> exceptionClass : exceptionClassses) {
                        if (exception.getClass().equals(exceptionClass)) {
                            return result;
                        }
                    }
                } catch (NoSuchMethodException e) {
                    return result;
                }

                // 未在方法签名上定义的异常，在服务器端打印 ERROR 日志
                // for the exception not found in method's signature, print ERROR message in server's log.
                logger.error("Got unchecked and undeclared exception which called by " + RpcContext.getContext().getRemoteHost()
                        + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName()
                        + ", exception: " + exception.getClass().getName() + ": " + exception.getMessage(), exception);

                // directly throw if exception class and interface class are in the same jar file.
                // 异常类和接口类在同一 jar 包里，直接抛出，因为，服务消费者可以反序列化该异常（芋道源码：？？？）
                String serviceFile = ReflectUtils.getCodeBase(invoker.getInterface());
                String exceptionFile = ReflectUtils.getCodeBase(exception.getClass());
                if (serviceFile == null || exceptionFile == null || serviceFile.equals(exceptionFile)) {
                    return result;
                }
                
                // 是JDK自带的异常，直接抛出
                // directly throw if it's JDK exception
                String className = exception.getClass().getName();
                if (className.startsWith("java.") || className.startsWith("javax.")) {
                    return result;
                }
                // directly throw if it's dubbo exception
                // 是Dubbo本身的异常，直接抛出
                if (exception instanceof RpcException) {
                    return result;
                }

                // otherwise, wrap with RuntimeException and throw back to the client
                // 否则，包装成RuntimeException抛给客户端
                return new RpcResult(new RuntimeException(StringUtils.toString(exception)));
            } catch (Throwable e) {
                logger.warn("Fail to ExceptionFilter when called by " + RpcContext.getContext().getRemoteHost()
                        + ". service: " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName()
                        + ", exception: " + e.getClass().getName() + ": " + e.getMessage(), e);
                return result;
            }
        }
        return result;
    }

}

