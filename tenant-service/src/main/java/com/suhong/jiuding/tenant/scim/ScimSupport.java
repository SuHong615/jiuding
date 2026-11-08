/*
 * Copyright 2026 suhong
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
 *
 * @author suhong
 */
package com.suhong.jiuding.tenant.scim;

import com.suhong.jiuding.tenant.support.TimeUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON shape helpers for the SCIM 2.0 resources and error schema (RFC 7643/7644). */
final class ScimSupport {

    static final String SCIM_JSON = "application/scim+json";
    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    static final String USER_EXTENSION = "urn:tenancy:params:2.0:User";
    static final String GROUP_EXTENSION = "urn:tenancy:params:2.0:Group";
    static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
    static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private ScimSupport() {
    }

    static Map<String, Object> error(int status, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("schemas", List.of(ERROR_SCHEMA));
        error.put("status", status);
        error.put("detail", detail);
        return error;
    }

    static Map<String, Object> listResponse(long totalResults, int startIndex,
                                            List<Map<String, Object>> resources) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemas", List.of(LIST_SCHEMA));
        response.put("totalResults", totalResults);
        response.put("startIndex", startIndex);
        response.put("itemsPerPage", resources.size());
        response.put("Resources", resources);
        return response;
    }

    static Map<String, Object> meta(String resourceType, LocalDateTime created, LocalDateTime updated,
                                    String location) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resourceType", resourceType);
        meta.put("created", TimeUtil.iso(created));
        meta.put("lastModified", TimeUtil.iso(updated));
        if (location != null) {
            meta.put("location", location);
        }
        return meta;
    }

    static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    static String nestedString(Map<String, Object> body, String parent, String key) {
        Object value = body == null ? null : body.get(parent);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object nested = map.get(key);
        return nested instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    static boolean bool(Map<String, Object> body, String key, boolean fallback) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    /** Reads {@code value} from the first element of a multi-valued SCIM attribute. */
    static String firstValue(Map<String, Object> body, String attribute) {
        Object raw = body == null ? null : body.get(attribute);
        if (raw instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) {
                Object value = map.get("value");
                return value instanceof String text ? text : null;
            }
            return first instanceof String text ? text : null;
        }
        return raw instanceof String text ? text : null;
    }

    static List<String> values(Map<String, Object> body, String attribute) {
        List<String> values = new ArrayList<>();
        Object raw = body == null ? null : body.get(attribute);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object value = map.get("value");
                    if (value instanceof String text && !text.isBlank()) {
                        values.add(text.trim());
                    }
                } else if (item instanceof String text && !text.isBlank()) {
                    values.add(text.trim());
                }
            }
        } else if (raw instanceof String text && !text.isBlank()) {
            values.add(text.trim());
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> extension(Map<String, Object> body, String uri) {
        Object raw = body == null ? null : body.get(uri);
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    static String extensionString(Map<String, Object> body, String uri, String key) {
        Map<String, Object> extension = extension(body, uri);
        return extension == null ? null : str(extension, key);
    }

    static List<Map<String, Object>> asMapList(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    result.add(typed);
                }
            }
        }
        return result;
    }

    static Map<String, Object> membersResource(List<ScimDirectoryRepository.ScimAccountRow> accounts) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (ScimDirectoryRepository.ScimAccountRow account : accounts) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("value", account.id());
            member.put("display", account.displayName());
            member.put("$ref", "/scim/v2/Users/" + account.id());
            members.add(member);
        }
        return Map.of("members", members);
    }

    /** Supported SCIM filter: {@code attribute eq "value"} for userName/displayName/active. */
    static Filter parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String expression = raw;
        // MockMvc / raw query strings may arrive percent-encoded; the servlet container
        // normally decodes them already, so only decode when an escape sequence is present.
        if (expression.contains("%") || expression.contains("+")) {
            try {
                expression = java.net.URLDecoder.decode(expression, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // keep the raw expression; the parser below will reject it
            }
        }
        if (expression.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^\\s*([A-Za-z.]+)\\s+eq\\s+(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s]+))\\s*$",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(expression);
        if (!matcher.matches()) {
            throw ScimException.badRequest("unsupported filter expression: " + expression);
        }
        String attribute = canonicalAttribute(matcher.group(1));
        String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if ("active".equals(attribute) && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw ScimException.badRequest("active filter must compare with true or false");
        }
        return new Filter(attribute, value);
    }

    private static String canonicalAttribute(String raw) {
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "username" -> "userName";
            case "displayname" -> "displayName";
            case "active" -> "active";
            default -> throw ScimException.badRequest("unsupported filter attribute: " + raw);
        };
    }

    /** Validates SCIM pagination parameters (startIndex is 1-based, count is capped at 100). */
    static Page page(Integer startIndex, Integer count) {
        int start = startIndex == null ? 1 : startIndex;
        if (start < 1) {
            throw ScimException.badRequest("startIndex must be >= 1");
        }
        int size = count == null ? 50 : count;
        if (size < 1) {
            throw ScimException.badRequest("count must be >= 1");
        }
        size = Math.min(size, 100);
        return new Page(start, size, (long) (start - 1) * size);
    }

    /** Extracts a scalar string from a PATCH operation value (String/Number/Boolean or Map.value). */
    static String valueText(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("value");
            return nested == null ? null : String.valueOf(nested);
        }
        return value == null ? null : String.valueOf(value);
    }

    /** Extracts account ids from a SCIM members value (list of maps with {@code value} or strings). */
    static List<String> memberIds(Object value) {
        List<String> ids = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String id = valueText(item);
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        } else if (value != null) {
            String id = valueText(value);
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    record Filter(String attribute, String value) {
    }

    record Page(int startIndex, int count, long offset) {
    }
}
