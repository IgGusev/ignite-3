/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.cli.core.exception.handler;

import org.apache.ignite.internal.cli.core.exception.ExceptionHandler;
import org.apache.ignite.internal.cli.core.exception.ExceptionWriter;
import org.apache.ignite.internal.cli.core.exception.UnitNotFoundException;
import org.apache.ignite.internal.cli.core.style.component.ErrorUiComponent;
import org.apache.ignite.internal.cli.core.style.element.UiElements;

/** Exception handler for {@link UnitNotFoundException}. */
public class UnitNotFoundExceptionHandler implements ExceptionHandler<UnitNotFoundException> {
    @Override
    public int handle(ExceptionWriter err, UnitNotFoundException e) {
        err.write(
                ErrorUiComponent.builder()
                        .header("Unit not found")
                        .details(UiElements.unit(e.unitId(), e.version()))
                        .build().render()
        );

        return 1;
    }

    @Override
    public Class<UnitNotFoundException> applicableException() {
        return UnitNotFoundException.class;
    }
}
