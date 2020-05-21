/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.config.server.utils;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * write response
 *
 * @author Nacos
 */
@Slf4j
public class ResponseUtil {

    public static void writeErrMsg(HttpServletResponse response, int httpCode,
                                   String msg) {
        response.setStatus(httpCode);
        try {
            response.getWriter().println(msg);
        } catch (IOException e) {
            log.error("ResponseUtil:writeErrMsg wrong", e);
        }
    }
}
