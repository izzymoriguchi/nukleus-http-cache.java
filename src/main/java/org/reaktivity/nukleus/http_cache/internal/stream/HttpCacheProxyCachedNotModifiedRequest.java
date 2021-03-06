/**
 * Copyright 2016-2020 The Reaktivity Project
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

import org.agrona.DirectBuffer;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.http_cache.internal.types.Array32FW;
import org.reaktivity.nukleus.http_cache.internal.types.HttpHeaderFW;
import org.reaktivity.nukleus.http_cache.internal.types.OctetsFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.DataFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.EndFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.HttpBeginExFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.ResetFW;

final class HttpCacheProxyCachedNotModifiedRequest
{
    private final HttpCacheProxyFactory factory;
    private final MessageConsumer acceptReply;
    private final long acceptRouteId;
    private final long acceptReplyId;
    private final long acceptInitialId;
    private final int initialWindow;

    HttpCacheProxyCachedNotModifiedRequest(
        HttpCacheProxyFactory factory,
        MessageConsumer acceptReply,
        long acceptRouteId,
        long acceptInitialId)
    {
        this.factory = factory;
        this.acceptReply = acceptReply;
        this.acceptRouteId = acceptRouteId;
        this.acceptInitialId = acceptInitialId;
        this.acceptReplyId = factory.supplyReplyId.applyAsLong(acceptInitialId);
        this.initialWindow = factory.initialWindowSize;
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
            onRequestBegin(begin);
            break;
        case DataFW.TYPE_ID:
            final DataFW data = factory.dataRO.wrap(buffer, index, index + length);
            onRequestData(data);
            break;
        case EndFW.TYPE_ID:
            final EndFW end = factory.endRO.wrap(buffer, index, index + length);
            onRequestEnd(end);
            break;
        case AbortFW.TYPE_ID:
            final AbortFW abort = factory.abortRO.wrap(buffer, index, index + length);
            onRequestAbort(abort);
            break;
        }
    }

    private void onRequestBegin(
        BeginFW begin)
    {
        final OctetsFW extension = begin.extension();
        final HttpBeginExFW httpBeginFW = extension.get(factory.httpBeginExRO::wrap);
        final Array32FW<HttpHeaderFW> requestHeaders = httpBeginFW.headers();
        final long traceId = begin.traceId();

        factory.writer.doWindow(acceptReply,
                                acceptRouteId,
                                acceptInitialId,
                                traceId,
                                0L,
                                initialWindow,
                                0);

        // count all responses
        factory.counters.requestsCacheable.getAsLong();
        factory.counters.responsesCached.getAsLong();
        factory.counters.responses.getAsLong();

        factory.router.setThrottle(acceptReplyId, this::onResponseMessage);

        factory.writer.do304(acceptReply,
                             acceptRouteId,
                             acceptReplyId,
                             traceId,
                             requestHeaders);
    }

    private void onRequestData(
        final DataFW data)
    {
        factory.writer.doWindow(acceptReply,
                                acceptRouteId,
                                acceptInitialId,
                                data.traceId(),
                                data.budgetId(),
                                data.reserved(),
                                0);
    }

    private void onRequestEnd(
        final EndFW end)
    {
        factory.writer.doHttpEnd(acceptReply,
                                 acceptRouteId,
                                 acceptReplyId,
                                 end.traceId());
    }

    private void onRequestAbort(
        final AbortFW abort)
    {
        factory.writer.doAbort(acceptReply,
                               acceptRouteId,
                               acceptReplyId,
                               abort.traceId());
    }

    private void onResponseMessage(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        switch (msgTypeId)
        {
        case ResetFW.TYPE_ID:
            final ResetFW reset = factory.resetRO.wrap(buffer, index, index + length);
            onResponseReset(reset);
            break;
        default:
            break;
        }
    }

    private void onResponseReset(
        ResetFW reset)
    {
        factory.writer.doReset(acceptReply,
                               acceptRouteId,
                               acceptInitialId,
                               reset.traceId());
    }
}
