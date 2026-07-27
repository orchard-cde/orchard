package dev.orchard.fence.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Authenticates public device-flow clients (like trowel-cli) that have no client secret,
 * so none of Spring Authorization Server's built-in client authentication methods apply.
 * The device authorization request and the device-code-grant token request both carry
 * only a {@code client_id} parameter; the actual registered-client lookup is left to
 * {@link DeviceClientAuthenticationProvider}.
 */
public final class DeviceClientAuthenticationConverter implements AuthenticationConverter {

    private final RequestMatcher deviceAuthorizationRequestMatcher;
    private final RequestMatcher deviceAccessTokenRequestMatcher;

    public DeviceClientAuthenticationConverter(String deviceAuthorizationEndpointUri, String tokenEndpointUri) {
        RequestMatcher clientIdParameterMatcher =
                request -> request.getParameter(OAuth2ParameterNames.CLIENT_ID) != null;

        this.deviceAuthorizationRequestMatcher = new AndRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, deviceAuthorizationEndpointUri),
                clientIdParameterMatcher);

        RequestMatcher deviceCodeGrantMatcher = request ->
                AuthorizationGrantType.DEVICE_CODE.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE));
        this.deviceAccessTokenRequestMatcher = new AndRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, tokenEndpointUri),
                deviceCodeGrantMatcher,
                clientIdParameterMatcher);
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!this.deviceAuthorizationRequestMatcher.matches(request)
                && !this.deviceAccessTokenRequestMatcher.matches(request)) {
            return null;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            // Let the standard converters handle clients that did authenticate.
            return null;
        }

        String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
        if (!StringUtils.hasText(clientId)) {
            return null;
        }

        Map<String, Object> additionalParameters = new HashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (!OAuth2ParameterNames.CLIENT_ID.equals(name) && values.length > 0) {
                additionalParameters.put(name, values[0]);
            }
        });

        return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null, additionalParameters);
    }
}
