package myvertx.turtest.test;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import myvertx.turtest.verticle.MainVerticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
public class TestMainVerticle {

    @BeforeEach
    void deploy_verticle(final Vertx vertx, final VertxTestContext testContext) {
        vertx.deployVerticle(new MainVerticle(), testContext.succeeding(id -> testContext.completeNow()));
    }

    @Test
    void verticle_deployed(final Vertx vertx, final VertxTestContext testContext) {
        testContext.completeNow();
    }
}
