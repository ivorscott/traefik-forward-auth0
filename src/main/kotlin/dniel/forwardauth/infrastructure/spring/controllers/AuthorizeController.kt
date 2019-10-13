package dniel.forwardauth.infrastructure.spring.controllers

import dniel.forwardauth.application.AuthorizeHandler
import dniel.forwardauth.application.CommandDispatcher
import dniel.forwardauth.domain.shared.User
import dniel.forwardauth.infrastructure.spring.exceptions.AuthorizationException
import dniel.forwardauth.infrastructure.spring.exceptions.PermissionDeniedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.Locale
import java.util.stream.Collectors
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletResponse


/**
 * Authorize Endpoint.
 * This endpoint is used by traefik forward properties to authorize requests.
 * It will return 200 for requests that has a valid JWT_TOKEN and will
 * redirect other to authenticate at Auth0.
 */
@RestController
class AuthorizeController(val authorizeHandler: AuthorizeHandler, val commandDispatcher: CommandDispatcher) {
    private val LOGGER = LoggerFactory.getLogger(this.javaClass)

    /**
     * Authorize Endpoint
     */
    @RequestMapping("/authorize", method = [RequestMethod.GET])
    fun authorize(@RequestHeader headers: MultiValueMap<String, String>,
                  @CookieValue("ACCESS_TOKEN", required = false) accessTokenCookie: String?,
                  @CookieValue("JWT_TOKEN", required = false) userinfoCookie: String?,
                  @RequestHeader("Accept") acceptContent: String?,
                  @RequestHeader("x-requested-with") requestedWithHeader: String?,
                  @RequestHeader("x-forwarded-host") forwardedHostHeader: String,
                  @RequestHeader("x-forwarded-proto") forwardedProtoHeader: String,
                  @RequestHeader("x-forwarded-uri") forwardedUriHeader: String,
                  @RequestHeader("x-forwarded-method") forwardedMethodHeader: String,
                  response: HttpServletResponse): ResponseEntity<Unit> {
        printHeaders(headers)
        return authenticateToken(acceptContent, requestedWithHeader,
                accessTokenCookie, userinfoCookie, forwardedMethodHeader,
                forwardedHostHeader, forwardedProtoHeader, forwardedUriHeader, response)
    }


    /**
     * Authenticate
     *
     */
    private fun authenticateToken(acceptContent: String?, requestedWithHeader: String?, accessToken: String?,
                                  idToken: String?, method: String, host: String, protocol: String,
                                  uri: String, response: HttpServletResponse): ResponseEntity<Unit> {

        val authorizeResult = handleCommand(acceptContent, requestedWithHeader, accessToken, idToken, protocol, host, uri, method)
        return when (authorizeResult) {
            is AuthorizeHandler.AuthEvent.AccessDenied -> throw PermissionDeniedException(authorizeResult)
            is AuthorizeHandler.AuthEvent.Error -> throw AuthorizationException(authorizeResult)
            is AuthorizeHandler.AuthEvent.NeedRedirect -> {
                redirect(authorizeResult, response)
            }
            is AuthorizeHandler.AuthEvent.AccessGranted -> {
                accessGranted(accessToken, authorizeResult)
            }
        }
    }

    /**
     * Execute AuthorizeCommand
     *
     */
    private fun handleCommand(acceptContent: String?, requestedWithHeader: String?, accessToken: String?, idToken: String?,
                              protocol: String, host: String, uri: String, method: String): AuthorizeHandler.AuthEvent {
        val isApi = (acceptContent != null && acceptContent.contains("application/json")) ||
                requestedWithHeader != null && requestedWithHeader == "XMLHttpRequest"
        val principal = SecurityContextHolder.getContext().authentication.principal as User

        val command: AuthorizeHandler.AuthorizeCommand = AuthorizeHandler.AuthorizeCommand(principal, protocol, host, uri, method, isApi)
        return commandDispatcher.dispatch(authorizeHandler, command) as AuthorizeHandler.AuthEvent
    }

    /**
     * Access Granted.
     *
     */
    private fun accessGranted(accessToken: String?, authorizeResult: AuthorizeHandler.AuthEvent.AccessGranted): ResponseEntity<Unit> {
        val builder = ResponseEntity.noContent()
        builder.header("Authorization", "Bearer ${accessToken}")
        authorizeResult.userinfo.forEach { k, v ->
            val headerName = "X-Forwardauth-${k.replace('_', '-')}".toLowerCase(Locale.ENGLISH)
            LOGGER.trace("Add header ${headerName} with value ${v}")
            builder.header(headerName, v)
        }
        return builder.build()
    }

    /**
     * Redirect to Authorize
     *
     */
    private fun redirect(authorizeResult: AuthorizeHandler.AuthEvent.NeedRedirect, response: HttpServletResponse): ResponseEntity<Unit> {
        // add the nonce value to the request to be able to retrieve ut again on the singin endpoint.
        val nonceCookie = Cookie("AUTH_NONCE", authorizeResult.nonce.value)
        nonceCookie.domain = authorizeResult.cookieDomain
        nonceCookie.maxAge = 60
        nonceCookie.isHttpOnly = true
        nonceCookie.path = "/"
        response.addCookie(nonceCookie)
        LOGGER.debug("Redirect to ${authorizeResult.authorizeUrl}")
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(authorizeResult.authorizeUrl).build()
    }

    /**
     * Debug print headers
     */
    private fun printHeaders(headers: MultiValueMap<String, String>) {
        if (LOGGER.isTraceEnabled) {
            headers.forEach { (key, value) -> LOGGER.trace(String.format("Header '%s' = %s", key, value.stream().collect(Collectors.joining("|")))) }
        }
    }
}