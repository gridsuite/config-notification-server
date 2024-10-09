/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.config.notification.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.gridsuite.config.notification.server.NotificationWebSocketHandler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author Abdelsalem Hedhili <abdelsalem.hedhili at rte-france.com>
 */
class NotificationWebSocketHandlerTest {

    private ObjectMapper objectMapper;
    private WebSocketSession ws;
    private HandshakeInfo handshakeinfo;

    @BeforeEach
    void setup() {
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

    private void withFilters(String filterUserId, String filterAppName) {
        var notificationWebSocketHandler = new NotificationWebSocketHandler(objectMapper, Integer.MAX_VALUE);

        UriComponentsBuilder uriComponentBuilder = UriComponentsBuilder.fromUriString("http://localhost:1234/notify");

        if (filterAppName != null) {
            uriComponentBuilder.queryParam(QUERY_APP_NAME, filterAppName);
        }
        when(handshakeinfo.getUri()).thenReturn(uriComponentBuilder.build().toUri());

        HttpHeaders httpHeaders = new HttpHeaders();
        if (filterUserId != null) {
            httpHeaders.add(HEADER_USER_ID, filterUserId);
            when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);
        } else {
            when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);
        }

        var atomicRef = new AtomicReference<FluxSink<Message<String>>>();
        var flux = Flux.create(atomicRef::set);
        notificationWebSocketHandler.consumeNotification().accept(flux);
        var sink = atomicRef.get();

        notificationWebSocketHandler.handle(ws);

        List<Map<String, Object>> refMessages = Arrays.asList(

                Map.of(HEADER_USER_ID, "userId"),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, ""),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "app1"),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "app2"),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, COMMON_APP_NAME),

                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "", HEADER_PARAMETER_NAME, ""),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "app1", HEADER_PARAMETER_NAME, ""),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "app2", HEADER_PARAMETER_NAME, ""),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, COMMON_APP_NAME, HEADER_PARAMETER_NAME, ""),

                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "", HEADER_PARAMETER_NAME, "param"),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "app1", HEADER_PARAMETER_NAME, "param"),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "app2", HEADER_PARAMETER_NAME, "param"),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, COMMON_APP_NAME, HEADER_PARAMETER_NAME, "param"),

                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "appName", HEADER_PARAMETER_NAME, "param1"),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "appName", HEADER_PARAMETER_NAME, "param2"),
                Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, COMMON_APP_NAME, HEADER_PARAMETER_NAME, "param3"),

                Map.of(HEADER_USER_ID, "userId2", HEADER_APP_NAME, "", HEADER_PARAMETER_NAME, "param"),
                Map.of(HEADER_USER_ID, "userId2", HEADER_APP_NAME, "app1", HEADER_PARAMETER_NAME, "param"),
                Map.of(HEADER_USER_ID, "userId2", HEADER_APP_NAME, "app2", HEADER_PARAMETER_NAME, "param"),
                Map.of(HEADER_USER_ID, "userId2", HEADER_APP_NAME, COMMON_APP_NAME, HEADER_PARAMETER_NAME, "param"),

                Map.of(HEADER_USER_ID, "foo bar/bar", HEADER_APP_NAME, "appName"),      // test encoding user name

                Map.of("foo bar/bar", "foo bar/bar", HEADER_APP_NAME, "appName")    // bad header for user id (message discarded)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Flux<WebSocketMessage>> argument = ArgumentCaptor.forClass(Flux.class);
        verify(ws).send(argument.capture());
        List<String> messages = new ArrayList<>();
        argument.getValue().map(WebSocketMessage::getPayloadAsText).subscribe(messages::add);
        refMessages.stream().map(headers -> new GenericMessage<>("", headers)).forEach(sink::next);
        sink.complete();

        List<Map<String, Object>> expected = refMessages.stream()
                .filter(headers -> {
                    String userId = (String) headers.get(HEADER_USER_ID);
                    String appName = (String) headers.get(HEADER_APP_NAME);
                    return filterUserId.equals(userId) && (filterAppName == null || COMMON_APP_NAME.equals(appName) || filterAppName.equals(appName));
                })
                .map(NotificationWebSocketHandlerTest::toResultHeader)
                .collect(Collectors.toList());

        List<Map<String, Object>> actual = messages.stream()
                .map(t -> {
                    try {
                        return toResultHeader(((Map<String, Map<String, Object>>) objectMapper.readValue(t, Map.class)).get("headers"));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toList());

        assertEquals(expected, actual);
    }

    private static Map<String, Object> toResultHeader(Map<String, Object> messageHeader) {
        var resHeader = new HashMap<String, Object>();
        if (messageHeader.get(HEADER_APP_NAME) != null) {
            resHeader.put(HEADER_APP_NAME, messageHeader.get(HEADER_APP_NAME));
        }
        if (messageHeader.get(HEADER_PARAMETER_NAME) != null) {
            resHeader.put(HEADER_PARAMETER_NAME, messageHeader.get(HEADER_PARAMETER_NAME));
        }
        return resHeader;
    }

    @Test
    void testWithoutUserIdFilter() {
        try {
            withFilters(null, null);
        } catch (NotificationServerRuntimeException e) {
            if (!e.getMessage().equals(NotificationServerRuntimeException.NOT_ALLOWED)) {
                fail();
            }
        }
        try {
            withFilters(null, "appName");
        } catch (NotificationServerRuntimeException e) {
            if (!e.getMessage().equals(NotificationServerRuntimeException.NOT_ALLOWED)) {
                fail();
            }
        }
    }

    @Test
    void testUserIdFilter() {
        withFilters("userId", null);
    }

    @Test
    void testAppNameFilter() {
        withFilters("userId", "appName");
    }

    @Test
    void testEncodingCharacters() {
        withFilters("foo bar/bar", "appName");
    }

    @Test
    void testHeartbeat() {
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
    void testDiscard() {
        var notificationWebSocketHandler = new NotificationWebSocketHandler(objectMapper, Integer.MAX_VALUE);

        UriComponentsBuilder uriComponentBuilder = UriComponentsBuilder.fromUriString("http://localhost:1234/notify");

        when(handshakeinfo.getUri()).thenReturn(uriComponentBuilder.build().toUri());
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(HEADER_USER_ID, "userId1");
        when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);

        var atomicRef = new AtomicReference<FluxSink<Message<String>>>();
        var flux = Flux.create(atomicRef::set);
        notificationWebSocketHandler.consumeNotification().accept(flux);
        var sink = atomicRef.get();
        Map<String, Object> headers = Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "appName");

        sink.next(new GenericMessage<>("", headers)); // should be discarded, no client connected

        notificationWebSocketHandler.handle(ws);

        ArgumentCaptor<Flux<WebSocketMessage>> argument1 = ArgumentCaptor.forClass(Flux.class);
        verify(ws).send(argument1.capture());
        List<String> messages1 = new ArrayList<>();
        Flux<WebSocketMessage> out1 = argument1.getValue();
        Disposable d1 = out1.map(WebSocketMessage::getPayloadAsText).subscribe(messages1::add);
        d1.dispose();

        sink.next(new GenericMessage<>("", headers)); // should be discarded, first client disconnected

        notificationWebSocketHandler.handle(ws);

        ArgumentCaptor<Flux<WebSocketMessage>> argument2 = ArgumentCaptor.forClass(Flux.class);
        verify(ws, times(2)).send(argument2.capture());
        List<String> messages2 = new ArrayList<>();
        Flux<WebSocketMessage> out2 = argument2.getValue();
        Disposable d2 = out2.map(WebSocketMessage::getPayloadAsText).subscribe(messages2::add);
        d2.dispose();

        sink.complete();
        assertEquals(0, messages1.size());
        assertEquals(0, messages2.size());
    }
}
