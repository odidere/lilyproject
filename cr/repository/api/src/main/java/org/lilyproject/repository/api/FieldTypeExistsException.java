/*
 * Copyright 2010 Outerthought bvba
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package org.lilyproject.repository.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Thrown when trying to create a field type with a QName which is already used by another field type.
 */
public class FieldTypeExistsException extends TypeException {
    private String fieldType;

    public FieldTypeExistsException(String message, Map<String, String> state) {
        this.fieldType = state.get("fieldType");
    }

    @Override
    public Map<String, String> getState() {
        Map<String, String> state = new HashMap<String, String>();
        state.put("fieldType", fieldType);
        return state;
    }

    public FieldTypeExistsException(FieldType fieldType) {
        if (fieldType != null) {
            this.fieldType = fieldType.getName().toString();
        }
    }

    @Override
    public String getMessage() {
        StringBuilder message = new StringBuilder();
        message.append("FieldType '").append(fieldType).append("' ").append("already exists");
        return message.toString();
    }
}
