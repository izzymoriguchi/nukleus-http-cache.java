package org.reaktivity.nukleus.http_cache.internal.stream;

import org.agrona.DirectBuffer;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.http_cache.internal.types.HttpHeaderFW;
import org.reaktivity.nukleus.http_cache.internal.types.ListFW;

public abstract class HttpCacheProxyRequest
{
    public final HttpCacheProxyFactory streamFactory;
    public final long acceptRouteId;
    public final long acceptStreamId;
    public final MessageConsumer acceptReply;

    public final long acceptReplyId;

    public final MessageConsumer connectInitial;
    public final MessageConsumer connectReply;
    public final long connectRouteId;
    public final long connectReplyId;
    public final long connectInitialId;

    HttpCacheProxyRequest(
        HttpCacheProxyFactory streamFactory,
        MessageConsumer acceptReply,
        long acceptRouteId,
        long acceptStreamId,
        long acceptReplyId,
        MessageConsumer connectInitial,
        MessageConsumer connectReply,
        long connectInitialId,
        long connectReplyId,
        long connectRouteId)
    {

        this.streamFactory = streamFactory;
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

    abstract void onRequestMessage(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length);

    abstract void onResponseMessage(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length);

    abstract HttpCacheProxyResponse newResponse(
        ListFW<HttpHeaderFW> responseHeaders);

}
