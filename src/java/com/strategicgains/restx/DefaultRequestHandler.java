/*
 * Copyright 2009, Strategic Gains, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
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
package com.strategicgains.restx;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpRequest;

import com.strategicgains.restx.exception.ServiceException;
import com.strategicgains.restx.response.DefaultHttpResponseWriter;
import com.strategicgains.restx.response.ErrorHttpResponseWriter;
import com.strategicgains.restx.response.HttpResponseWriter;
import com.strategicgains.restx.route.Action;
import com.strategicgains.restx.route.RouteResolver;
import com.strategicgains.restx.serialization.SerializationProcessor;

/**
 * @author toddf
 * @since Nov 13, 2009
 */
@Sharable
public class DefaultRequestHandler
extends SimpleChannelUpstreamHandler
{
	// SECTION: CONSTANTS

	private static final String JSONP_CALLBACK = "jsonp";

	
	// SECTION: INSTANCE VARIABLES

	private RouteResolver routeResolver;
	private Resolver<SerializationProcessor> serializationResolver;
	private HttpResponseWriter responseWriter;
	private HttpResponseWriter errorResponseWriter;


	// SECTION: CONSTRUCTORS

	public DefaultRequestHandler(RouteResolver routeResolver, Resolver<SerializationProcessor> serializationResolver)
	{
		this(routeResolver, serializationResolver, new DefaultHttpResponseWriter(), new ErrorHttpResponseWriter());
	}

	public DefaultRequestHandler(RouteResolver routeResolver, Resolver<SerializationProcessor> serializationResolver,
		HttpResponseWriter responseWriter, HttpResponseWriter errorResponseWriter)
	{
		super();
		this.routeResolver = routeResolver;
		this.serializationResolver = serializationResolver;
		setResponseWriter(responseWriter);
		setErrorResponseWriter(errorResponseWriter);
	}


	// SECTION: MUTATORS
	
	public HttpResponseWriter getResponseWriter()
	{
		return this.responseWriter;
	}
	
	public HttpResponseWriter getErrorResponseWriter()
	{
		return this.errorResponseWriter;
	}

	public void setResponseWriter(HttpResponseWriter writer)
	{
		this.responseWriter = writer;
	}
	
	public void setErrorResponseWriter(HttpResponseWriter writer)
	{
		this.errorResponseWriter = writer;
	}


	// SECTION: SIMPLE-CHANNEL-UPSTREAM-HANDLER

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent event)
	throws Exception
	{
		Request request = createRequest((HttpRequest) event.getMessage(), ctx);
		Response response = createResponse(request);

		try
		{
			preProcessRequest(request);
			Action action = routeResolver.resolve(request);
			Object result = action.invoke(request, response);
			
			if (action.shouldSerializeResponse())
			{
				SerializationProcessor p = serializationResolver.resolve(request);
				response.setBody(serializeResult(result, p, request));
				response.addHeader(CONTENT_TYPE, p.getResultingContentType());
			}
			else
			{
				response.addHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");
				response.setBody(result);
			}

			postProcessResponse(request, response);
		}
		catch (ServiceException e)
		{
			e.printStackTrace();
			response.setResponseStatus(e.getHttpStatus());
			response.setException(e);
		}
		catch (Throwable t)
		{
			t.printStackTrace();
			response.setResponseStatus(INTERNAL_SERVER_ERROR);
			response.setException(t);
		}
		finally
		{
			if (response.hasException())
			{
				writeError(ctx, request, response);
			}
			else
			{
				// Set response and accept headers, if appropriate.
				writeResponse(ctx, request, response);
			}
		}
	}

	/**
	 * Handles the JSONP case, wrapping the serialized JSON string in the callback function, if applicable.
	 * If there is a JSONP header value and the serialization processor returns application/json, then
	 * the serialization results are wrapped in the JSONP method call.
	 * 
	 * @param result
	 * @param procesor
	 * @param request
	 * @return
	 */
	private Object serializeResult(Object result, SerializationProcessor processor, Request request)
	{
		String serialized = processor.serialize(result);
		String callback = request.getHeader(JSONP_CALLBACK);
		
		if (callback != null 
			&& processor.getResultingContentType().toLowerCase().contains("json"))
		{
        	StringBuilder sb = new StringBuilder();
        	sb.append(callback).append("(").append(serialized).append(")");
        	return sb.toString();			
		}
		
		return serialized;
	}

	/**
     * @param request
     */
    protected void preProcessRequest(Request request)
    {
	    // Default: do nothing.
    }

	/**
     * @param request
     * @param response
     */
    protected void postProcessResponse(Request request, Response response)
    {
	    // Default: do nothing.
    }

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent event)
	throws Exception
	{
		event.getCause().printStackTrace();
		event.getChannel().close();
	}

	/**
     * @param request
     * @return
     */
    private Request createRequest(HttpRequest request, ChannelHandlerContext context)
    {
    	return new Request(request, serializationResolver, routeResolver);
    }

	/**
     * @param request
     * @return
     */
    private Response createResponse(Request request)
    {
    	return new Response(request);
    }

    /**
     * @param message
     * @return
     */
    private void writeResponse(ChannelHandlerContext ctx, Request request, Response response)
    {
    	getResponseWriter().write(ctx, request, response);
    }

	private void writeError(ChannelHandlerContext ctx, Request request, Response response)
	{
		getErrorResponseWriter().write(ctx, request, response);
	}
}
