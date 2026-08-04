# Config Notification Server

[![Actions Status](https://github.com/gridsuite/config-notification-server/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/gridsuite/config-notification-server/actions)
[![Coverage Status](https://sonarcloud.io/api/project_badges/measure?project=org.gridsuite%3Aconfig-notification-server&metric=coverage)](https://sonarcloud.io/component_measures?id=org.gridsuite%3Aconfig-notification-server&metric=coverage)
[![MPL-2.0 License](https://img.shields.io/badge/license-MPL_2.0-blue.svg)](https://www.mozilla.org/en-US/MPL/2.0/)

## Description

The **config-notification-server** is a microservice of the [GridSuite](https://github.com/gridsuite) platform responsible for **forwarding configuration change notifications and global announcements to front-end clients via WebSocket**.

It provides the following capabilities:

- **Forward per-user configuration update notifications** from the RabbitMQ broker to connected WebSocket clients, filtering messages by `userId` and optionally by `appName`.
- **Broadcast global announcements** (e.g. maintenance messages with severity and duration) to all connected front-end clients.
- **Keep WebSocket connections alive** with periodic heartbeat pings.

It is used by all GridSuite front-ends.

---

## Technical Stack

- Spring Boot (WebFlux, Actuator, Cloud Stream)
- RabbitMQ via Spring Cloud Stream
- WebSocket
- Micrometer / Prometheus

---

## Development Scripts

Build Docker image

```shell
mvn install -DskipTests -Dpowsybl.docker.install
```

---

## Interactions with Other Microservices

```text
┌──────────────────────┐
│    config-server     │  (publishes parameter change events and global announcements)
└──────────────────────┘
          ▼
    RabbitMQ (config.update / config.message)
          ▼
┌──────────────────────────────────┐
│   config-notification-server     │  (consumes and forwards to WebSocket clients)
└──────────────────────────────────┘
          ▼
     WebSocket clients (all GridSuite front-ends)
```

---

## WebSocket Endpoints

Two WebSocket endpoints are exposed:

### `/notify` — Per-user configuration update notifications

Clients connect with:
- **Header** `userId` (required): used to filter notifications for the authenticated user.
- **Query parameter** `appName` (optional): filter notifications for a specific application. Notifications with `appName=common` are always forwarded regardless of this filter.

Messages have the following JSON structure:

```json
{
  "payload": "<message payload>",
  "headers": {
    "appName": "<application name>",
    "parameterName": "<parameter name>"
  }
}
```

### `/global` — Global announcements

No filtering is applied. Every connected client receives all messages.

Messages have the following JSON structure:

```json
{
  "payload": "<message payload>",
  "headers": {
    "messageType": "<type>",
    "announcementId": "<id>",
    "severity": "<severity>",
    "duration": "<duration>"
  }
}
```

---

