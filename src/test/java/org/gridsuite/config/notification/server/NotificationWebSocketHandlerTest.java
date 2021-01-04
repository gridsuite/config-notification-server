/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.config.notification.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

/**
 * @author Abdelsalem Hedhili <abdelsalem.hedhili at rte-france.com>
 */
public class NotificationWebSocketHandlerTest {

    private ObjectMapper objectMapper;
    private WebSocketSession ws;
    private HandshakeInfo handshakeinfo;

    @Before
    public void setup() {
        objectMapper = new ObjectMapper();
        var dataBufferFactory = new DefaultDataBufferFactory();

        ws = Mockito.mock(WebSocketSession.class);
        handshakeinfo = Mockito.mock(HandshakeInfo.class);

        when(ws.getHandshakeInfo()).thenReturn(handshakeinfo);
        when(ws.receive()).thenReturn(Flux.empty());
        when(ws.send(any())).thenReturn(Mono.empty());
        when(ws.textMessage(any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            String str = (String) args[0];
            return new WebSocketMessage(WebSocketMessage.Type.TEXT, dataBufferFactory.wrap(str.getBytes()));
        });
        when(ws.pingMessage(any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            Function<DataBufferFactory, DataBuffer> f = (Function<DataBufferFactory, DataBuffer>) args[0];
            return new WebSocketMessage(WebSocketMessage.Type.PING, f.apply(dataBufferFactory));
        });
        when(ws.getId()).thenReturn("testsession");

    }

    private void withFilters(String filterUserId) {
        var notificationWebSocketHandler = new NotificationWebSocketHandler(objectMapper, Integer.MAX_VALUE);

        UriComponentsBuilder uriComponentBuilder = UriComponentsBuilder.fromUriString("http://localhost:1234/notify");

        uriComponentBuilder.queryParam("userId", filterUserId);
        when(handshakeinfo.getUri()).thenReturn(uriComponentBuilder.build().toUri());

        if (filterUserId != null) {
            ArrayList<String> values = new ArrayList<>();
            values.add("userId");
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.put("userId", values);
            when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);
        } else {
            HttpHeaders httpHeaders = new HttpHeaders();
            when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);
        }

        var atomicRef = new AtomicReference<FluxSink<Message<String>>>();
        var flux = Flux.create(atomicRef::set);
        notificationWebSocketHandler.consumeNotification().accept(flux);
        var sink = atomicRef.get();

        notificationWebSocketHandler.handle(ws);

        List<Map<String, Object>> refMessages = Arrays.asList(
                Map.of("userId", "userId1"),
                Map.of("userId", "userId2"),
                Map.of("userId", "userId3"),
                Map.of("userId", "userId4"),
                Map.of("userId", "userId5"),
                Map.of("userId", "userId6"),
                Map.of("userId", "userId7"),
                Map.of("userId", "userId8"),
                Map.of("userId", "userId9"),
                Map.of("foo bar/bar", "foo bar/bar")
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Flux<WebSocketMessage>> argument = ArgumentCaptor.forClass(Flux.class);
        verify(ws).send(argument.capture());
        List<String> messages = new ArrayList<String>();
        argument.getValue().map(WebSocketMessage::getPayloadAsText).subscribe(messages::add);
        refMessages.stream().map(headers -> new GenericMessage<String>("", headers)).forEach(sink::next);
        sink.complete();

        List<Map<String, Object>> expected = refMessages.stream().filter(headers -> {
            String userId = (String) headers.get("userId");
            return filterUserId == null || filterUserId.equals(userId);
        }).collect(Collectors.toList());
        List<Map<String, Object>> actual = messages.stream().map(t -> {
            try {
                var deserializedHeaders = ((Map<String, Map<String, Object>>) objectMapper.readValue(t, Map.class))
                        .get("headers");
                return Map.of("userId", deserializedHeaders.get("userId"));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
        assertEquals(expected, actual);
    }

    @Test
    public void testWithoutFilter() {
        try {
            withFilters(null);
        } catch (NotificationServerRuntimeException e) {
            if (!e.getMessage().equals(NotificationServerRuntimeException.NOT_ALLOWED)) {
                fail();
            }
        }
    }

    @Test
    public void testUserIdFilter() {
        withFilters("userId");
    }

    @Test
    public void testEncodingCharacters() {
        withFilters("foo bar/bar");
    }

    @Test
    public void testHeartbeat() {
        var notificationWebSocketHandler = new NotificationWebSocketHandler(null, 1);

        UriComponentsBuilder uriComponentBuilder = UriComponentsBuilder.fromUriString("http://localhost:1234/notify");

        when(handshakeinfo.getUri()).thenReturn(uriComponentBuilder.build().toUri());
        ArrayList<String> values = new ArrayList<>();
        values.add("userId");
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.put("userId", values);
        when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);

        var flux = Flux.<Message<String>>empty();
        notificationWebSocketHandler.consumeNotification().accept(flux);
        notificationWebSocketHandler.handle(ws);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Flux<WebSocketMessage>> argument = ArgumentCaptor.forClass(Flux.class);
        verify(ws).send(argument.capture());
        assertEquals("testsession-0", argument.getValue().blockFirst(Duration.ofSeconds(10)).getPayloadAsText());
    }

    @Test
    public void testDiscard() {
        var notificationWebSocketHandler = new NotificationWebSocketHandler(objectMapper, Integer.MAX_VALUE);

        UriComponentsBuilder uriComponentBuilder = UriComponentsBuilder.fromUriString("http://localhost:1234/notify");

        when(handshakeinfo.getUri()).thenReturn(uriComponentBuilder.build().toUri());
        ArrayList<String> values = new ArrayList<>();
        values.add("userId");
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.put("userId", values);
        when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);

        var atomicRef = new AtomicReference<FluxSink<Message<String>>>();
        var flux = Flux.create(atomicRef::set);
        notificationWebSocketHandler.consumeNotification().accept(flux);
        var sink = atomicRef.get();
        Map<String, Object> headers = Map.of("userId", "foo");

        sink.next(new GenericMessage<String>("", headers)); // should be discarded, no client connected

        notificationWebSocketHandler.handle(ws);

        ArgumentCaptor<Flux<WebSocketMessage>> argument1 = ArgumentCaptor.forClass(Flux.class);
        verify(ws).send(argument1.capture());
        List<String> messages1 = new ArrayList<String>();
        Flux<WebSocketMessage> out1 = argument1.getValue();
        Disposable d1 = out1.map(WebSocketMessage::getPayloadAsText).subscribe(messages1::add);
        d1.dispose();

        sink.next(new GenericMessage<String>("", headers)); // should be discarded, first client disconnected

        notificationWebSocketHandler.handle(ws);

        ArgumentCaptor<Flux<WebSocketMessage>> argument2 = ArgumentCaptor.forClass(Flux.class);
        verify(ws, times(2)).send(argument2.capture());
        List<String> messages2 = new ArrayList<String>();
        Flux<WebSocketMessage> out2 = argument2.getValue();
        Disposable d2 = out1.map(WebSocketMessage::getPayloadAsText).subscribe(messages1::add);
        d2.dispose();

        sink.complete();
        assertEquals(0, messages1.size());
        assertEquals(0, messages2.size());
    }
}
