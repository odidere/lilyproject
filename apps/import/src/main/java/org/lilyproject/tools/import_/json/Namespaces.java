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
package org.lilyproject.tools.import_.json;

import java.util.Map;

public interface Namespaces {

    boolean usePrefixes();

    String getOrMakePrefix(String namespace);

    void addMapping(String prefix, String namespace);

    String getNamespace(String prefix);

    String getPrefix(String namespace);

    Map<String, String> getNsToPrefixMapping();
}
