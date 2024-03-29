package myvertx.turtest.svc.impl;

import java.util.Map;

import javax.inject.Inject;

import com.github.f4b6a3.ulid.UlidCreator;
import com.google.inject.Singleton;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.generator.ImageCaptchaGenerator;
import cloud.tianai.captcha.generator.ImageTransform;
import cloud.tianai.captcha.generator.common.model.dto.ImageCaptchaInfo;
import cloud.tianai.captcha.generator.impl.CacheImageCaptchaGenerator;
import cloud.tianai.captcha.generator.impl.MultiImageCaptchaGenerator;
import cloud.tianai.captcha.generator.impl.transform.Base64ImageTransform;
import cloud.tianai.captcha.resource.ImageCaptchaResourceManager;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.resource.impl.DefaultImageCaptchaResourceManager;
import cloud.tianai.captcha.validator.ImageCaptchaValidator;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import cloud.tianai.captcha.validator.impl.BasicCaptchaTrackValidator;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;
import myvertx.turtest.clone.MapStructRegister;
import myvertx.turtest.config.MainProperties;
import myvertx.turtest.ra.CaptchaGenRa;
import myvertx.turtest.svc.CaptchaRedisSvc;
import myvertx.turtest.svc.CaptchaSvc;
import myvertx.turtest.to.CaptchaRedisSetTo;
import myvertx.turtest.to.CaptchaVerifyTo;
import rebue.wheel.vertx.ro.Vro;

@Slf4j
@Singleton
public class CaptchaSvcImpl implements CaptchaSvc {

    private ImageCaptchaGenerator imageCaptchaGenerator;

    // 负责计算一些数据存到缓存中，用于校验使用
    // ImageCaptchaValidator负责校验用户滑动滑块是否正确和生成滑块的一些校验数据; 比如滑块到凹槽的百分比值
    private final ImageCaptchaValidator imageCaptchaValidator = new BasicCaptchaTrackValidator();

    @Inject
    private MainProperties              mainProperties;

    @Inject
    private CaptchaRedisSvc             captchaRedisSvc;

    public CaptchaSvcImpl() {
        final ImageCaptchaResourceManager imageCaptchaResourceManager = new DefaultImageCaptchaResourceManager();

        initCaptchaGenerator(imageCaptchaResourceManager);

        loadCaptchaResource(imageCaptchaResourceManager);
    }

    /**
     * 初始化Captcha生成器
     *
     * @param imageCaptchaResourceManager
     */
    private void initCaptchaGenerator(final ImageCaptchaResourceManager imageCaptchaResourceManager) {
        final ImageTransform imageTransform = new Base64ImageTransform();
        this.imageCaptchaGenerator = new CacheImageCaptchaGenerator(
                new MultiImageCaptchaGenerator(imageCaptchaResourceManager, imageTransform), 10, 1000, 100);
        this.imageCaptchaGenerator.init(true);
    }

    /**
     * 加载Captcha资源
     *
     * @param imageCaptchaResourceManager
     */
    private void loadCaptchaResource(final ImageCaptchaResourceManager imageCaptchaResourceManager) {
        final ResourceStore resourceStore = imageCaptchaResourceManager.getResourceStore();
        // 添加自定义背景图片
        resourceStore.addResource(CaptchaTypeConstant.ROTATE, new Resource("classpath", "img/bg/01.png"));
        resourceStore.addResource(CaptchaTypeConstant.ROTATE, new Resource("classpath", "img/bg/02.jpg"));
        resourceStore.addResource(CaptchaTypeConstant.ROTATE, new Resource("classpath", "img/bg/03.jpg"));
        resourceStore.addResource(CaptchaTypeConstant.ROTATE, new Resource("classpath", "img/bg/04.jpg"));
    }

    /**
     * 生成验证码
     */
    @Override
    public Future<Vro> gen() {
        log.debug("captcha gen");
        /*
         * 生成验证码图片, 可选项
         * SLIDER (滑块验证码)
         * ROTATE (旋转验证码)
         * CONCAT (滑动还原验证码)
         * WORD_IMAGE_CLICK (文字点选验证码)
         *
         * 更多验证码支持 详见 cloud.tianai.captcha.common.constant.CaptchaTypeConstant
         */
        final ImageCaptchaInfo    imageCaptchaInfo = this.imageCaptchaGenerator.generateCaptchaImage(CaptchaTypeConstant.ROTATE);
        // 这个map数据应该存到缓存中，校验的时候需要用到该数据
        final Map<String, Object> map              = this.imageCaptchaValidator.generateImageCaptchaValidData(imageCaptchaInfo);
        // 生成缓存的key
        final String              captchaId        = UlidCreator.getUlid().toLowerCase();

        final CaptchaGenRa        captchaGenRa     = CaptchaGenRa.builder()
                .id(captchaId)
                .backgroundImage(imageCaptchaInfo.getBackgroundImage())
                .sliderImage(imageCaptchaInfo.getSliderImage())
                .build();
        final Vro                 vro              = Vro.success("获取并生成验证码成功", captchaGenRa);

        if (this.mainProperties.getIsMock()) {
            return Future.succeededFuture(vro);
        }

        return this.captchaRedisSvc.setCaptcha(new CaptchaRedisSetTo(captchaId, map))
                .compose(res -> {
                    log.debug("redis.getCaptcha result: {}", res);
                    return Future.succeededFuture(vro);
                })
                .recover(err -> Future.succeededFuture(
                        Vro.fail("获取并生成验证码失败", err.getMessage())));
    }

    /**
     * 校验验证码
     *
     * @param to 校验验证码的参数
     */
    @Override
    public Future<Vro> verify(final CaptchaVerifyTo to) {
        log.debug("captcha verify: {}", to);
        if (to.getTrackList() == null || to.getTrackList().isEmpty()) {
            final String msg = "校验验证码参数错误";
            return Future.succeededFuture(Vro.illegalArgument(msg));
        }

        if (this.mainProperties.getIsMock()) {
            return Future.succeededFuture(Vro.success("通过验证"));
        }

        return this.captchaRedisSvc.getCaptcha(to.getCaptchaId())
                .compose(ra -> {
                    if (ra == null) {
                        return Future.succeededFuture(Vro.warn("验证失败", "查找不到验证码ID: " + to.getCaptchaId()));
                    }

                    log.debug("获取到缓存中的验证码信息: {}", ra);

                    final Map<String, Object> map = ra.getMap();

                    // 用户传来的行为轨迹和进行校验
                    // - imageCaptchaTrack为前端传来的滑动轨迹数据
                    // - map 为生成验证码时缓存的map数据
                    final ImageCaptchaTrack imageCaptchaTrack = MapStructRegister.INSTANCE.toImageCaptchaTrack(to);
                    final boolean   check             = this.imageCaptchaValidator.valid(imageCaptchaTrack, map);
                    return Future.succeededFuture(check ? Vro.success("通过验证") : Vro.warn("验证不通过"));
                })
                .recover(err -> Future.succeededFuture(Vro.fail("校验验证码失败", err.getMessage())));
    }

}
