package dniel.forwardauth.application

import dniel.forwardauth.domain.events.Event
import org.slf4j.LoggerFactory

/**
 * A logging command handler that wrap the dispatch command with log statements.
 */
class LoggingHandler<T : Command>(val handler: CommandHandler<T>) : CommandHandler<T> by handler {
    private val LOGGER = LoggerFactory.getLogger(this.javaClass)

    override fun handle(params: T): Event {
        val simpleName = handler.javaClass.simpleName
        val start = System.currentTimeMillis()
        val result = handler.handle(params)
        val end = System.currentTimeMillis() - start
        LOGGER.info("Handle Command ${simpleName} Execution time: " + end + "ms. result=${result.javaClass.simpleName}")
        return result
    }
}