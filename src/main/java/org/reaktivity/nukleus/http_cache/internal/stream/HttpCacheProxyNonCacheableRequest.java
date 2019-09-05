/**
 * Copyright 2016-2019 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.http_cache.internal.stream;

import static java.lang.System.currentTimeMillis;
import static org.reaktivity.nukleus.http_cache.internal.HttpCacheConfiguration.DEBUG;
import static org.reaktivity.nukleus.http_cache.internal.stream.util.HttpHeadersUtil.getRequestURL;

import org.agrona.DirectBuffer;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.http_cache.internal.types.HttpHeaderFW;
import org.reaktivity.nukleus.http_cache.internal.types.ListFW;
import org.reaktivity.nukleus.http_cache.internal.types.OctetsFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.DataFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.EndFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.HttpBeginExFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.WindowFW;

final class HttpCacheProxyNonCacheableRequest
{
    private final HttpCacheProxyFactory factory;
    private final long acceptRouteId;
    private final long acceptStreamId;
    private final long acceptReplyId;
    private final MessageConsumer acceptReply;

    private final MessageConsumer connectInitial;
    private final MessageConsumer connectReply;
    private final long connectRouteId;
    private final long connectReplyId;
    private final long connectInitialId;

    HttpCacheProxyNonCacheableRequest(
        HttpCacheProxyFactory factory,
        MessageConsumer acceptReply,
        long acceptRouteId,
        long acceptReplyId,
        long acceptStreamId,
        MessageConsumer connectInitial,
        MessageConsumer connectReply,
        long connectInitialId,
        long connectReplyId,
        long connectRouteId)
    {
        this.factory = factory;
        this.acceptReply = acceptReply;
        this.acceptRouteId = acceptRouteId;
        this.acceptStreamId = acceptStreamId;
        this.acceptReplyId = acceptReplyId;
        this.connectInitial = connectInitial;
        this.connectReply = connectReply;
        this.connectRouteId = connectRouteId;
        this.connectReplyId = connectReplyId;
        this.connectInitialId = connectInitialId;
    }

    MessageConsumer newResponse(
        HttpBeginExFW beginEx)
    {
        final HttpCacheProxyNonCacheableResponse nonCacheableResponse =
            new HttpCacheProxyNonCacheableResponse(factory,
                                                   connectReply,
                                                   connectRouteId,
                                                   connectReplyId,
                                                   acceptReply,
                                                   acceptRouteId,
                                                   acceptReplyId);
        factory.router.setThrottle(acceptReplyId, nonCacheableResponse::onResponseMessage);
        return nonCacheableResponse::onResponseMessage;
    }

    void onResponseMessage(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch(msgTypeId)
        {
            case ResetFW.TYPE_ID:
                factory.writer.doReset(acceptReply,
                                       acceptRouteId,
                                       acceptStreamId,
                                       factory.supplyTrace.getAsLong());
                factory.correlations.remove(connectReplyId);
                break;
        }
    }

   void onRequestMessage(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
            case BeginFW.TYPE_ID:
                final BeginFW begin = factory.beginRO.wrap(buffer, index, index + length);
                onBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = factory.dataRO.wrap(buffer, index, index + length);
                onData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = factory.endRO.wrap(buffer, index, index + length);
                onEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = factory.abortRO.wrap(buffer, index, index + length);
                onAbort(abort);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = factory.windowRO.wrap(buffer, index, index + length);
                onWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = factory.resetRO.wrap(buffer, index, index + length);
                onReset(reset);
                break;
            default:
                break;
        }
    }

    private void onBegin(
        BeginFW begin)
    {
        final OctetsFW extension = begin.extension();
        final HttpBeginExFW httpBeginEx = extension.get(factory.httpBeginExRO::tryWrap);
        assert httpBeginEx != null;
        final ListFW<HttpHeaderFW> requestHeaders = httpBeginEx.headers();

        // count all requests
        factory.counters.requests.getAsLong();

        if (DEBUG)
        {
            System.out.printf("[%016x] ACCEPT %016x %s [received request]\n",
                    currentTimeMillis(), acceptReplyId, getRequestURL(httpBeginEx.headers()));
        }

        long connectReplyId = factory.supplyReplyId.applyAsLong(connectInitialId);

        if (DEBUG)
        {
            System.out.printf("[%016x] CONNECT %016x %s [sent proxy request]\n",
                              currentTimeMillis(), connectReplyId, getRequestURL(requestHeaders));
        }

        factory.writer.doHttpRequest(
            connectInitial,
            connectRouteId,
            connectInitialId,
            factory.supplyTrace.getAsLong(),
            builder -> requestHeaders.forEach(
                h ->  builder.item(item -> item.name(h.name()).value(h.value()))
                                             ));

        factory.router.setThrottle(connectInitialId, this::onRequestMessage);
    }

    private void onData(
        final DataFW data)
    {
            final long groupId = data.groupId();
            final int padding = data.padding();
            final OctetsFW payload = data.payload();

            factory.writer.doHttpData(connectInitial,
                                      connectRouteId,
                                      connectInitialId,
                                      data.trace(),
                                      groupId,
                                      payload.buffer(),
                                      payload.offset(),
                                      payload.sizeof(),
                                      padding);
    }

    private void onEnd(
        final EndFW end)
    {
        final long traceId = end.trace();
        factory.writer.doHttpEnd(connectInitial, connectRouteId, connectInitialId, traceId);
    }

    private void onAbort(
        final AbortFW abort)
    {
        final long traceId = abort.trace();
        factory.writer.doAbort(connectInitial, connectRouteId, connectInitialId, traceId);
    }

    private void onWindow(
        final WindowFW window)
    {
        final int credit = window.credit();
        final int padding = window.padding();
        final long groupId = window.groupId();
        final long traceId = window.trace();
        factory.writer.doWindow(acceptReply, acceptRouteId, acceptStreamId, traceId, credit, padding, groupId);
    }

    private void onReset(
        final ResetFW reset)
    {
        final long traceId = reset.trace();
        factory.writer.doReset(acceptReply, acceptRouteId, acceptStreamId, traceId);
    }

}