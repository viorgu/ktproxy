import io.vertx.core.Vertx
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test

class SimpleTest {

    @Test
    fun test() = runBlocking<Unit> {
        val vertx = Vertx.vertx()
        val server = vertx.createHttpServer()

        server.requestHandler {

        }

        server.listen(0)
    }

}