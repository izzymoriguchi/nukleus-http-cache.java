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

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.reaktivity.nukleus.buffer.BufferPool.NO_SLOT;
import static org.reaktivity.nukleus.http_cache.internal.proxy.cache.DefaultCacheEntry.NUM_OF_HEADER_SLOTS;
import static org.reaktivity.nukleus.http_cache.internal.proxy.cache.PreferHeader.getPreferWait;
import static org.reaktivity.nukleus.http_cache.internal.proxy.cache.PreferHeader.isPreferIfNoneMatch;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.CACHE_ENTRY_ABORTED_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.CACHE_ENTRY_NOT_MODIFIED_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.CACHE_ENTRY_UPDATED_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.GROUP_REQUEST_RESET_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.INITIATE_REQUEST_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.REQUEST_EXPIRED_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.util.HttpHeaders.ETAG;
import static org.reaktivity.nukleus.http_cache.internal.stream.util.HttpHeaders.IF_NONE_MATCH;
import static org.reaktivity.nukleus.http_cache.internal.stream.util.HttpHeaders.PREFER;
import static org.reaktivity.nukleus.http_cache.internal.stream.util.HttpHeadersUtil.HAS_IF_NONE_MATCH;
import static org.reaktivity.nukleus.http_cache.internal.stream.util.HttpHeadersUtil.getHeader;

import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.MutableInteger;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.http_cache.internal.proxy.cache.DefaultCacheEntry;
import org.reaktivity.nukleus.http_cache.internal.types.ArrayFW;
import org.reaktivity.nukleus.http_cache.internal.types.HttpHeaderFW;
import org.reaktivity.nukleus.http_cache.internal.types.OctetsFW;
import org.reaktivity.nukleus.http_cache.internal.types.String16FW;
import org.reaktivity.nukleus.http_cache.internal.types.StringFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.DataFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.EndFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.HttpBeginExFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.SignalFW;
import org.reaktivity.nukleus.http_cache.internal.types.stream.WindowFW;

final class HttpCacheProxyCacheableRequest
{
    private static final StringFW HEADER_NAME_STATUS = new StringFW(":status");
    private static final String16FW HEADER_VALUE_STATUS_503 = new String16FW("503");

    private final HttpCacheProxyFactory factory;
    private final HttpProxyCacheableRequestGroup requestGroup;
    private final MutableInteger requestSlot;
    private final int initialWindow;

    private final MessageConsumer accept;
    private final long acceptRouteId;
    private final long acceptInitialId;
    private final long acceptReplyId;

    private MessageConsumer connect;
    private long connectRouteId;
    private long connectReplyId;
    private long connectInitialId;

    private String ifNoneMatch;
    private Future<?> preferWaitExpired;

    private int acceptReplyBudget;
    private long groupId;
    private int padding;
    private int payloadWritten = -1;
    private DefaultCacheEntry cacheEntry;
    private boolean etagSent;
    private boolean requestExpired;

    HttpCacheProxyCacheableRequest(
        HttpCacheProxyFactory factory,
        HttpProxyCacheableRequestGroup requestGroup,
        MessageConsumer accept,
        long acceptRouteId,
        long acceptInitialId,
        long acceptReplyId,
        MessageConsumer connect,
        long connectInitialId,
        long connectReplyId,
        long connectRouteId)
    {
        this.factory = factory;
        this.requestGroup = requestGroup;
        this.accept = accept;
        this.acceptRouteId = acceptRouteId;
        this.acceptInitialId = acceptInitialId;
        this.acceptReplyId = acceptReplyId;
        this.connect = connect;
        this.connectRouteId = connectRouteId;
        this.connectReplyId = connectReplyId;
        this.connectInitialId = connectInitialId;
        this.initialWindow = factory.responseBufferPool.slotCapacity();
        this.requestSlot =  new MutableInteger(NO_SLOT);
    }

    MessageConsumer newResponse(
        HttpBeginExFW beginEx)
    {
        MessageConsumer newStream;
        ArrayFW<HttpHeaderFW> responseHeaders = beginEx.headers();

        if (factory.defaultCache.matchCacheableResponse(requestGroup.getRequestHash(),
                                                             getHeader(responseHeaders, ETAG),
                                                             getRequestHeaders().anyMatch(HAS_IF_NONE_MATCH)))
        {
            newStream = new HttpCacheProxyNotModifiedResponse(factory,
                                                             requestGroup.getRequestHash(),
                                                             getHeader(getRequestHeaders(), PREFER),
                                                              accept,
                                                             acceptRouteId,
                                                             acceptReplyId,
                                                              connect,
                                                             connectReplyId,
                                                             connectRouteId)::onResponseMessage;
        }
        else
        {
            newStream = new HttpCacheProxyNonCacheableResponse(factory,
                                                               connect,
                                                               connectRouteId,
                                                               connectReplyId,
                                                               accept,
                                                               acceptRouteId,
                                                               acceptReplyId)::onResponseMessage;
        }

        factory.router.setThrottle(acceptReplyId, newStream);
        purge();
        resetRequestTimeoutIfNecessary();
        requestGroup.dequeue(ifNoneMatch, acceptReplyId);

        return newStream;
    }

    void onAcceptMessage(
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
        case ResetFW.TYPE_ID:
            final ResetFW reset = factory.resetRO.wrap(buffer, index, index + length);
            onResponseReset(reset);
            break;
        case WindowFW.TYPE_ID:
            final WindowFW window = factory.windowRO.wrap(buffer, index, index + length);
            onResponseWindow(window);
            break;
        case SignalFW.TYPE_ID:
            final SignalFW signal = factory.signalRO.wrap(buffer, index, index + length);
            onResponseSignal(signal);
            break;
        default:
            break;
        }
    }

    private void onRequestBegin(
        BeginFW begin)
    {
        final OctetsFW extension = begin.extension();
        final HttpBeginExFW httpBeginFW = extension.get(factory.httpBeginExRO::wrap);
        final ArrayFW<HttpHeaderFW> requestHeaders = httpBeginFW.headers();

        // count all requests
        factory.counters.requests.getAsLong();
        factory.counters.requestsCacheable.getAsLong();

        boolean stored = storeRequest(requestHeaders);
        if (!stored)
        {
            send503RetryAfter();
            return;
        }

        HttpHeaderFW ifNoneMatchHeader = requestHeaders.matchFirst(h -> IF_NONE_MATCH.equals(h.name().asString()));
        if (ifNoneMatchHeader != null)
        {
            ifNoneMatch = ifNoneMatchHeader.value().asString();
            schedulePreferWaitIfNoneMatchIfNecessary(requestHeaders);
        }

        requestGroup.enqueue(ifNoneMatch, acceptRouteId, acceptReplyId);
        factory.writer.doWindow(accept,
                                acceptRouteId,
                                acceptInitialId,
                                begin.trace(),
                                initialWindow,
                                0,
                                0L);

    }

    private void onRequestData(
        final DataFW data)
    {
        factory.writer.doWindow(accept,
                                acceptRouteId,
                                acceptInitialId,
                                data.trace(),
                                data.reserved(),
                                0,
                                data.groupId());
    }

    private void onRequestEnd(
        final EndFW end)
    {
        //NOOP
    }

    private void onRequestAbort(
        final AbortFW abort)
    {
        final long traceId = abort.trace();
        factory.writer.doAbort(connect, connectRouteId, connectInitialId, traceId);
        factory.writer.doReset(accept, acceptRouteId, acceptInitialId, traceId);
        cleanupRequestIfNecessary();
        resetRequestTimeoutIfNecessary();
    }

    private void schedulePreferWaitIfNoneMatchIfNecessary(
        ArrayFW<HttpHeaderFW> requestHeaders)
    {
        if (isPreferIfNoneMatch(requestHeaders))
        {
            int preferWait = getPreferWait(requestHeaders);
            if (preferWait > 0)
            {
                preferWaitExpired = factory.executor.schedule(Math.min(preferWait, factory.preferWaitMaximum),
                                                                   SECONDS,
                                                                   acceptRouteId,
                                                                   acceptReplyId,
                                                                   REQUEST_EXPIRED_SIGNAL);
            }
        }
    }

    private boolean storeRequest(
        final ArrayFW<HttpHeaderFW> headers)
    {
        assert requestSlot.value == NO_SLOT;
        int newRequestSlot = factory.requestBufferPool.acquire(acceptInitialId);
        if (newRequestSlot == NO_SLOT)
        {
            return false;
        }
        requestSlot.value = newRequestSlot;
        MutableDirectBuffer requestCacheBuffer = factory.requestBufferPool.buffer(requestSlot.value);
        requestCacheBuffer.putBytes(0, headers.buffer(), headers.offset(), headers.sizeof());
        return true;
    }

    private ArrayFW<HttpHeaderFW> getRequestHeaders()
    {
        final MutableDirectBuffer buffer = factory.requestBufferPool.buffer(requestSlot.value);
        return factory.requestHeadersRO.wrap(buffer, 0, buffer.capacity());
    }

    private void onResponseSignal(
        SignalFW signal)
    {
        final int signalId = (int) signal.signalId();

        switch (signalId)
        {
        case INITIATE_REQUEST_SIGNAL:
            onResponseSignalInitiateRequest(signal);
            break;
        case REQUEST_EXPIRED_SIGNAL:
            onResponseSignalRequestExpired(signal);
            break;
        case CACHE_ENTRY_NOT_MODIFIED_SIGNAL:
            onResponseSignalCacheEntryNotModified(signal);
            break;
        case CACHE_ENTRY_UPDATED_SIGNAL:
            onResponseSignalCacheEntryUpdated(signal);
            break;
        case CACHE_ENTRY_ABORTED_SIGNAL:
            onResponseSignalCacheEntryAborted(signal);
            break;
        case GROUP_REQUEST_RESET_SIGNAL:
            onResponseSignalGroupRequestReset(signal);
            break;
        default:
            break;
        }
    }

    private void onResponseSignalCacheEntryUpdated(
        SignalFW signal)
    {
        cacheEntry = factory.defaultCache.get(requestGroup.getRequestHash());
        if (payloadWritten == -1)
        {
            resetRequestTimeoutIfNecessary();
            sendHttpResponseHeaders(cacheEntry);
        }
        else
        {
            factory.budgetManager.resumeAssigningBudget(groupId, 0, signal.trace());
            sendEndIfNecessary(signal.trace());
        }
    }

    private void onResponseSignalRequestExpired(
        SignalFW signal)
    {
        factory.defaultCache.send304(getHeader(getRequestHeaders(), IF_NONE_MATCH),
                                     getHeader(getRequestHeaders(), PREFER),
                                     accept,
                                     acceptRouteId,
                                     acceptReplyId);
        requestExpired = true;
    }

    private void onResponseSignalCacheEntryAborted(
        SignalFW signal)
    {
        if (this.payloadWritten >= 0)
        {
            factory.writer.doAbort(accept,
                                   acceptRouteId,
                                   acceptReplyId,
                                   signal.trace());
            requestGroup.dequeue(ifNoneMatch, acceptReplyId);
            cacheEntry.setSubscribers(-1);
        }
        else
        {
            send503RetryAfter();
        }
        cleanupRequestIfNecessary();
    }

    private void onResponseSignalGroupRequestReset(
        SignalFW signal)
    {
        cleanupRequestIfNecessary();
        send503RetryAfter();
    }

    private void onResponseSignalInitiateRequest(
        SignalFW signal)
    {
        final ArrayFW<HttpHeaderFW> requestHeaders = getRequestHeaders();
        Consumer<ArrayFW.Builder<HttpHeaderFW.Builder, HttpHeaderFW>> mutator =
            builder -> requestHeaders.forEach(h -> builder.item(item -> item.name(h.name()).value(h.value())));
        connect = factory.writer.newHttpStream(requestGroup::newRequest,
                                               connectRouteId,
                                               connectInitialId,
                                               factory.supplyTrace.getAsLong(),
                                               mutator, (t, b, i, l) -> {});

        factory.writer.doHttpRequest(connect,
                                     connectRouteId,
                                     connectInitialId,
                                     factory.supplyTrace.getAsLong(),
                                     mutator);
    }

    private void onResponseSignalCacheEntryNotModified(
        SignalFW signal)
    {
        factory.defaultCache.send304(getHeader(getRequestHeaders(), IF_NONE_MATCH),
                                     getHeader(getRequestHeaders(), PREFER),
                                     accept,
                                     acceptRouteId,
                                     acceptReplyId);
        resetRequestTimeoutIfNecessary();
        requestExpired = true;
    }

    private void onResponseWindow(
        WindowFW window)
    {
        if (requestExpired)
        {
            factory.writer.doHttpEnd(accept, acceptRouteId, acceptReplyId, factory.supplyTrace.getAsLong());
            cleanupRequestIfNecessary();
        }
        else
        {
            groupId = window.groupId();
            padding = window.padding();
            long streamId = window.streamId();
            int credit = window.credit();
            acceptReplyBudget += credit;
            factory.budgetManager.window(BudgetManager.StreamKind.CACHE,
                                         groupId,
                                         streamId,
                                         credit,
                                         this::writePayload,
                                         window.trace());
            sendEndIfNecessary(window.trace());
        }
    }

    private void onResponseReset(
        ResetFW reset)
    {
        factory.budgetManager.closed(BudgetManager.StreamKind.CACHE,
                                     groupId,
                                     acceptReplyId,
                                     factory.supplyTrace.getAsLong());
        cleanupRequestIfNecessary();
        if (cacheEntry != null)
        {
            cacheEntry.setSubscribers(-1);
        }
    }

    private void send503RetryAfter()
    {
        factory.writer.doHttpResponse(
            accept,
            acceptRouteId,
            acceptReplyId,
            factory.supplyTrace.getAsLong(),
            e -> e.item(h -> h.name(HEADER_NAME_STATUS).value(HEADER_VALUE_STATUS_503))
                  .item(h -> h.name("retry-after").value("0")));

        factory.writer.doHttpEnd(
            accept,
            acceptRouteId,
            acceptReplyId,
            factory.supplyTrace.getAsLong());

        // count all responses
        factory.counters.responses.getAsLong();

        // count retry responses
        factory.counters.responsesRetry.getAsLong();
    }

    private void sendHttpResponseHeaders(
        DefaultCacheEntry cacheEntry)
    {
        final ArrayFW<HttpHeaderFW> responseHeaders = cacheEntry.getCachedResponseHeaders();

        if (cacheEntry.etag() != null)
        {
            etagSent = true;
        }

        factory.writer.doHttpResponseWithUpdatedHeaders(accept,
                                                        acceptRouteId,
                                                        acceptReplyId,
                                                        responseHeaders,
                                                        cacheEntry.getRequestHeaders(),
                                                        cacheEntry.etag(),
                                                        false,
                                                        factory.supplyTrace.getAsLong());

        payloadWritten = 0;

        factory.counters.responses.getAsLong();
    }

    private void sendEndIfNecessary(
        long traceId)
    {
        boolean ackedBudget = !factory.budgetManager.hasUnackedBudget(groupId, acceptReplyId);

        if (payloadWritten == cacheEntry.responseSize() &&
            ackedBudget &&
            cacheEntry.isResponseCompleted())
        {
            if (!etagSent &&
                cacheEntry.etag() != null)
            {
                factory.writer.doHttpEnd(accept,
                                         acceptRouteId,
                                         acceptReplyId,
                                         traceId,
                                         cacheEntry.etag());
            }
            else
            {
                factory.writer.doHttpEnd(accept,
                                         acceptRouteId,
                                         acceptReplyId,
                                         traceId);
            }

            factory.budgetManager.closed(BudgetManager.StreamKind.CACHE,
                                         groupId,
                                         acceptReplyId,
                                         traceId);
            cleanupRequestIfNecessary();
            cacheEntry.setSubscribers(-1);
        }
    }

    private int writePayload(
        int budget,
        long trace)
    {
        final int minBudget = min(budget, acceptReplyBudget);
        final int toWrite = min(minBudget - padding, cacheEntry.responseSize() - payloadWritten);
        if (toWrite > 0)
        {
            factory.writer.doHttpData(
                accept,
                acceptRouteId,
                acceptReplyId,
                trace,
                groupId,
                toWrite + padding,
                p -> buildResponsePayload(payloadWritten,
                                          toWrite,
                                          p,
                                          factory.defaultCache.getResponsePool()));
            payloadWritten += toWrite;
            budget -= toWrite + padding;
            acceptReplyBudget -= toWrite + padding;
            assert acceptReplyBudget >= 0;
        }

        return budget;
    }

    private void buildResponsePayload(
        int index,
        int length,
        OctetsFW.Builder p,
        BufferPool bp)
    {
        final int slotCapacity = bp.slotCapacity();
        final int startSlot = Math.floorDiv(index, slotCapacity) + NUM_OF_HEADER_SLOTS;
        buildResponsePayload(index, length, p, bp, startSlot);
    }

    private void buildResponsePayload(
        int index,
        int length,
        OctetsFW.Builder builder,
        BufferPool bp,
        int slotCnt)
    {
        if (length == 0)
        {
            return;
        }

        final int slotCapacity = bp.slotCapacity();
        int chunkedWrite = (slotCnt * slotCapacity) - index;
        int slot = cacheEntry.getResponseSlots().get(slotCnt);
        if (chunkedWrite > 0)
        {
            MutableDirectBuffer buffer = bp.buffer(slot);
            int offset = slotCapacity - chunkedWrite;
            int chunkLength = Math.min(chunkedWrite, length);
            builder.put(buffer, offset, chunkLength);
            index += chunkLength;
            length -= chunkLength;
        }
        buildResponsePayload(index, length, builder, bp, ++slotCnt);
    }

    private void resetRequestTimeoutIfNecessary()
    {
        if (preferWaitExpired != null)
        {
            preferWaitExpired.cancel(true);
            preferWaitExpired = null;
        }
    }

    private void cleanupRequestIfNecessary()
    {
        requestGroup.dequeue(ifNoneMatch, acceptReplyId);
        purge();
        factory.router.clearThrottle(connectReplyId);
        factory.router.clearThrottle(acceptReplyId);
    }

    private void purge()
    {
        if (requestSlot.value != NO_SLOT)
        {
            factory.requestBufferPool.release(requestSlot.value);
            requestSlot.value = NO_SLOT;
        }
    }
}
