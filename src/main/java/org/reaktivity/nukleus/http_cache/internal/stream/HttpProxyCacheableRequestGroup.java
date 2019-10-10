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

import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.CACHE_ENTRY_ABORTED_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.CACHE_ENTRY_NOT_MODIFIED_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.CACHE_ENTRY_UPDATED_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.INITIATE_REQUEST_SIGNAL;
import static org.reaktivity.nukleus.http_cache.internal.stream.Signals.REQUEST_IN_FLIGHT_ABORT_SIGNAL;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;

import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.MutableInteger;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.http_cache.internal.stream.util.Writer;

final class HttpProxyCacheableRequestGroup
{
    private final HashMap<String, Long2LongHashMap> requestsQueue;
    private final int requestHash;
    private final Writer writer;
    private final HttpCacheProxyFactory factory;
    private final Consumer<Integer> cleaner;
    private String etag;
    private long acceptRouteId;
    private long acceptReplyId;
    private String recentAuthorizationToken;

    HttpProxyCacheableRequestGroup(
        int requestHash,
        Writer writer,
        HttpCacheProxyFactory factory,
        Consumer<Integer> cleaner)
    {
        this.requestHash = requestHash;
        this.writer = writer;
        this.factory = factory;
        this.cleaner = cleaner;
        this.requestsQueue = new HashMap<>();
    }

    String getRecentAuthorizationToken()
    {
        return recentAuthorizationToken;
    }

    int getRequestHash()
    {
        return requestHash;
    }

    String getEtag()
    {
        return etag;
    }

    void setRecentAuthorizationToken(
        String recentAuthorizationToken)
    {
        this.recentAuthorizationToken = recentAuthorizationToken;
    }

    int getNumberOfRequests()
    {
        MutableInteger totalRequests = new MutableInteger();
        requestsQueue.forEach((key, routeIdsByReplyId) -> totalRequests.value += routeIdsByReplyId.size());
        return totalRequests.value;
    }

    void enqueue(
        String etag,
        long acceptRouteId,
        long acceptReplyId)
    {
        final boolean requestQueueIsEmpty = requestsQueue.isEmpty();
        final Long2LongHashMap routeIdsByReplyId = requestsQueue.computeIfAbsent(etag, this::createQueue);

        routeIdsByReplyId.put(acceptReplyId, acceptRouteId);

        if (requestQueueIsEmpty)
        {
            initiateRequest(etag, acceptRouteId, acceptReplyId);
        }
        else if (this.etag != null &&
                !this.etag.equals(etag))
        {
            doSignalInFlightRequestAborted(this.acceptRouteId, this.acceptReplyId);
            initiateRequest(null, acceptRouteId, acceptReplyId);
        }
    }

    void dequeue(
        String etag,
        long acceptReplyId)
    {
        final Long2LongHashMap routeIdsByReplyId = requestsQueue.get(etag);
        assert routeIdsByReplyId != null;

        final long acceptRouteId = routeIdsByReplyId.remove(acceptReplyId);
        assert acceptRouteId != routeIdsByReplyId.missingValue();

        if (routeIdsByReplyId.isEmpty())
        {
            requestsQueue.remove(etag);

            if (requestsQueue.isEmpty())
            {
                cleaner.accept(requestHash);
            }
        }

        if (!requestsQueue.isEmpty())
        {
            Long2LongHashMap.EntryIterator stream;

            if (!routeIdsByReplyId.isEmpty())
            {
                stream = routeIdsByReplyId.entrySet().iterator();
            }
            else
            {
                final Long2LongHashMap newRouteIdsByReplyId = requestsQueue.values().iterator().next();
                stream = newRouteIdsByReplyId.entrySet().iterator();
            }

            assert stream.hasNext();
            stream.next();
            writer.doSignal(stream.getLongKey(),
                            stream.getLongValue(),
                            factory.supplyTrace.getAsLong(),
                            INITIATE_REQUEST_SIGNAL);

        }
    }

    void onCacheableResponseUpdated(
        String etag)
    {
        requestsQueue.forEach((key, routeIdsByReplyId) ->
        {
            if (key != null && key.equals(etag) ||
                (key == null && etag == null) && requestsQueue.size() > 1)
            {
                routeIdsByReplyId.forEach(this::doSignalCacheEntryNotModified);
            }
            else
            {
                routeIdsByReplyId.forEach(this::doSignalCacheEntryUpdated);
            }
        });
    }

    void onCacheableResponseAborted()
    {
        requestsQueue.forEach((key, routeIdsByReplyId) ->
        {
            routeIdsByReplyId.forEach(this::doSignalCacheEntryAborted);
        });
    }

    MessageConsumer newRequest(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer sender)
    {
        return new HttpCacheProxyGroupRequest(factory,
                                              this,
                                              acceptReplyId,
                                              acceptRouteId)::onRequestMessage;
    }

    private void initiateRequest(
        String etag,
        long acceptRouteId,
        long acceptReplyId)
    {
        this.etag = etag;
        this.acceptRouteId = acceptRouteId;
        this.acceptReplyId = acceptReplyId;
        writer.doSignal(acceptRouteId,
                        acceptReplyId,
                        factory.supplyTrace.getAsLong(),
                        INITIATE_REQUEST_SIGNAL);
    }

    private void doSignalCacheEntryAborted(
        long acceptReplyId,
        long acceptRouteId)
    {
        writer.doSignal(acceptRouteId,
                        acceptReplyId,
                        factory.supplyTrace.getAsLong(),
                        CACHE_ENTRY_ABORTED_SIGNAL);
    }

    private void doSignalCacheEntryUpdated(
        long acceptReplyId,
        long acceptRouteId)
    {
        writer.doSignal(acceptRouteId,
                        acceptReplyId,
                        factory.supplyTrace.getAsLong(),
                        CACHE_ENTRY_UPDATED_SIGNAL);

    }

    private void doSignalCacheEntryNotModified(
        long acceptReplyId,
        long acceptRouteId)
    {
        writer.doSignal(acceptRouteId,
                        acceptReplyId,
                        factory.supplyTrace.getAsLong(),
                        CACHE_ENTRY_NOT_MODIFIED_SIGNAL);

    }

    private Long2LongHashMap createQueue(
        String etag)
    {
        Long2LongHashMap queue =  requestsQueue.get(etag);
        return Objects.requireNonNullElseGet(queue, () -> new Long2LongHashMap(-1));
    }


    private void doSignalInFlightRequestAborted(
        long acceptReplyId,
        long acceptRouteId)
    {
        writer.doSignal(acceptRouteId,
                        acceptReplyId,
                        factory.supplyTrace.getAsLong(),
                        REQUEST_IN_FLIGHT_ABORT_SIGNAL);
    }
}
