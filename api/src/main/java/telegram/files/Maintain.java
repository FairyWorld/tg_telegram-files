package telegram.files;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Vertx;
import telegram.files.maintains.AlbumCaptionMaintainVerticle;
import telegram.files.maintains.MaintainVerticle;
import telegram.files.maintains.ThumbnailMaintainVerticle;

public class Maintain {
    static {
        LogFactory.setCurrentLogFactory(new Config.JDKLogFactory());
    }

    private static final Log log = LogFactory.get();

    private static final Vertx vertx = Vertx.vertx();

    public static void main(String[] args) {
        if (ArrayUtil.isEmpty(args)) {
            System.out.println("Missing maintain name");
            System.out.println("Usage: java -cp api.jar telegram.files.Maintain <maintain-name>");
            System.out.println("Maintain names:");
            System.out.println("  album-caption");
            System.out.println("  thumbnail");
            System.exit(1);
        }

        String maintainName = args[0];
        try {
            MaintainVerticle maintainVerticle = null;
            switch (maintainName) {
                case "album-caption" -> {
                    maintainVerticle = new AlbumCaptionMaintainVerticle();
                    MessyUtils.await(vertx.deployVerticle(maintainVerticle, Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                            .onFailure(err -> {
                                log.error("Failed to deploy album caption maintain verticle", err);
                                System.exit(1);
                            }));
                }
                case "thumbnail" -> {
                    maintainVerticle = new ThumbnailMaintainVerticle();
                    MessyUtils.await(vertx.deployVerticle(maintainVerticle, Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                            .onFailure(err -> {
                                log.error("Failed to deploy thumbnail maintain verticle", err);
                                System.exit(1);
                            }));
                }
                default -> {
                    System.out.println("Unknown maintain name: " + maintainName);
                    System.exit(1);
                }
            }

            final MaintainVerticle finalMaintainVerticle = maintainVerticle;
            vertx.eventBus().consumer(EventEnum.MAINTAIN.address(), message ->
                    vertx.undeploy(finalMaintainVerticle.deploymentID())
                            .onSuccess(v -> {
                                log.trace("Undeploy maintain verticle success");
                                System.exit(0);
                            })
                            .onFailure(err -> {
                                log.error("Failed to undeploy maintain verticle", err);
                                System.exit(1);
                            }));
        } catch (Exception e) {
            log.error("Failed to maintain", e);
            System.exit(1);
        }
    }

}
