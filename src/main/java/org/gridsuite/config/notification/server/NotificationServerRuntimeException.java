/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.config.notification.server;

/**
 * @author Abdelsalem Hedhili <abdelsalem.hedhili at rte-france.com>
 */

public class NotificationServerRuntimeException extends RuntimeException {

    public static final String NOT_ALLOWED = "Not allowed : user id invalid";

    public NotificationServerRuntimeException(String message) {
        super(message);
    }
}
