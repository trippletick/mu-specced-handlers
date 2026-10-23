package io.github.jaylu.muspecced;

import io.muserver.rest.UriPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A mu-server URI template such as <code>/users/{id : [0-9]+}</code>, converted to an OpenAPI path such as
 * <code>/users/{id}</code>.
 */
final class PathTemplate {

    /** The OpenAPI path, e.g. <code>/api/users/{id}</code> */
    final String openApiPath;
    /** The path with parameter names removed, e.g. <code>/api/users/{}</code>, used to detect clashing routes */
    final String shape;
    /** Path parameter names in template order, mapped to their regex (or null when there is no regex) */
    final Map<String, String> params;

    private PathTemplate(String openApiPath, String shape, Map<String, String> params) {
        this.openApiPath = openApiPath;
        this.shape = shape;
        this.params = Collections.unmodifiableMap(params);
    }

    /**
     * @param prefix   A path prefix that the route handler does not see, e.g. a context path. May be null or empty.
     * @param template The template given to the route
     * @return The parsed template
     * @throws IllegalArgumentException if the template is not valid for mu-server
     */
    static PathTemplate parse(String prefix, String template) {
        if (template == null) {
            throw new IllegalArgumentException("uriTemplate cannot be null");
        }
        // Validates the template using the same rules as Routes.route(...)
        List<String> muNames = UriPattern.uriTemplateToRegex(template).namedGroups();

        Map<String, String> params = new LinkedHashMap<>();
        StringBuilder path = new StringBuilder();
        StringBuilder shape = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                path.append(c);
                shape.append(c);
                i++;
                continue;
            }
            // Like mu-server, a parameter ends at the first '}', so regexes cannot contain braces
            int end = template.indexOf('}', i);
            if (end == -1) {
                throw new IllegalArgumentException("Unclosed '{' in uriTemplate " + template);
            }
            int j = end + 1;
            String inner = template.substring(i + 1, end);
            int colon = inner.indexOf(':');
            String name = (colon == -1 ? inner : inner.substring(0, colon)).trim();
            String regex = colon == -1 ? null : inner.substring(colon + 1).trim();
            if (params.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate path parameter '" + name + "' in uriTemplate " + template);
            }
            params.put(name, regex == null || regex.isEmpty() ? null : regex);
            path.append('{').append(name).append('}');
            shape.append("{}");
            i = j;
        }

        if (!new ArrayList<>(params.keySet()).equals(muNames)) {
            throw new IllegalArgumentException("Could not parse path parameters of uriTemplate " + template
                + ": got " + params.keySet() + " but mu-server reports " + muNames);
        }

        return new PathTemplate(join(prefix, path.toString()), join(prefix, shape.toString()), params);
    }

    static String join(String prefix, String path) {
        String p = trimSlashes(prefix);
        String rest = trimSlashes(path);
        String joined = p.isEmpty() ? rest : rest.isEmpty() ? p : p + "/" + rest;
        return "/" + joined;
    }

    static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') start++;
        while (end > start && value.charAt(end - 1) == '/') end--;
        return value.substring(start, end);
    }
}
