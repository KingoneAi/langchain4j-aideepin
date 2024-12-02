package com.moyz.adi.common.service;

import cn.hutool.core.img.Img;
import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.moyz.adi.common.base.ThreadContext;
import com.moyz.adi.common.cosntant.RedisKeyConstant;
import com.moyz.adi.common.dto.*;
import com.moyz.adi.common.entity.*;
import com.moyz.adi.common.enums.ErrorEnum;
import com.moyz.adi.common.exception.BaseException;
import com.moyz.adi.common.helper.ImageModelContext;
import com.moyz.adi.common.helper.QuotaHelper;
import com.moyz.adi.common.helper.RateLimitHelper;
import com.moyz.adi.common.interfaces.AbstractImageModelService;
import com.moyz.adi.common.mapper.DrawMapper;
import com.moyz.adi.common.util.LocalCache;
import com.moyz.adi.common.util.LocalDateTimeUtil;
import com.moyz.adi.common.util.UuidUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.formula.functions.T;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.moyz.adi.common.cosntant.AdiConstant.GenerateImage.*;
import static com.moyz.adi.common.enums.ErrorEnum.*;

@Slf4j
@Service
public class DrawService extends ServiceImpl<DrawMapper, Draw> {

    @Resource
    @Lazy
    private DrawService self;

    @Value("${local.images}")
    private String imagePath;

    @Value("${local.watermark-images}")
    private String watermarkImagesPath;

    @Value("${local.thumbnails}")
    private String thumbnailsPath;

    @Value("${local.watermark-thumbnails}")
    private String watermarkThumbnailsPath;

    @Value("${adi.host}")
    private String adiHost;

    @Resource
    private QuotaHelper quotaHelper;

    @Resource
    private RateLimitHelper rateLimitHelper;

    @Resource
    private UserDayCostService userDayCostService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private FileService fileService;

    @Resource
    private AiModelService aiModelService;

    @Resource
    private DrawStarService drawStarService;

    @Resource
    private UserService userService;

    public void check() {
        User user = ThreadContext.getCurrentUser();
        String askingKey = MessageFormat.format(RedisKeyConstant.USER_DRAWING, user.getId());
        String askingVal = stringRedisTemplate.opsForValue().get(askingKey);
        //check 1: still waiting response
        if (StringUtils.isNotBlank(askingVal)) {
            throw new BaseException(A_DRAWING);
        }

        String requestTimesKey = MessageFormat.format(RedisKeyConstant.USER_REQUEST_TEXT_TIMES, user.getId());
        if (!rateLimitHelper.checkRequestTimes(requestTimesKey, LocalCache.TEXT_RATE_LIMIT_CONFIG)) {
            throw new BaseException(A_REQUEST_TOO_MUCH);
        }
        ErrorEnum errorEnum = quotaHelper.checkImageQuota(user, false);
        if (null != errorEnum) {
            throw new BaseException(errorEnum);
        }
    }

    /**
     * interacting method 1: Creates an image given a prompt
     *
     * @param generateImageReq 文生图请求参数
     */
    public String createByPrompt(GenerateImageReq generateImageReq) {
        self.check();
        CreateImageDto createImageDto = new CreateImageDto();
        createImageDto.setInteractingMethod(INTERACTING_METHOD_GENERATE_IMAGE);
        BeanUtils.copyProperties(generateImageReq, createImageDto);
        return self.generate(createImageDto);
    }

    /**
     * Interacting method 2:  Creates an edited or extended image given an original image and a prompt.
     */
    public String editByOriginalImage(EditImageReq editImageReq) {
        self.check();
        CreateImageDto createImageDto = new CreateImageDto();
        createImageDto.setInteractingMethod(INTERACTING_METHOD_EDIT_IMAGE);
        createImageDto.setPrompt(editImageReq.getPrompt());
        createImageDto.setSize(editImageReq.getSize());
        createImageDto.setNumber(editImageReq.getNumber());
        createImageDto.setMaskImage(editImageReq.getMaskImage());
        createImageDto.setOriginalImage(editImageReq.getOriginalImage());
        createImageDto.setModelName(editImageReq.getModelName());
        return self.generate(createImageDto);
    }

    /**
     * interacting method 3: Creates a variation of a given image.
     */
    public String variationImage(VariationImageReq variationImageReq) {
        self.check();
        CreateImageDto createImageDto = new CreateImageDto();
        createImageDto.setInteractingMethod(INTERACTING_METHOD_VARIATION);
        createImageDto.setSize(variationImageReq.getSize());
        createImageDto.setNumber(variationImageReq.getNumber());
        createImageDto.setOriginalImage(variationImageReq.getOriginalImage());
        return self.generate(createImageDto);
    }

    /**
     * 根据提示词生成图片
     *
     * @param createImageDto
     * @return
     */
    public String generate(CreateImageDto createImageDto) {
        AiModel aiModel = aiModelService.getByNameOrThrow(createImageDto.getModelName());
        User user = ThreadContext.getCurrentUser();
        int generateNumber = Math.min(createImageDto.getNumber(), user.getQuotaByImageDaily());
        String uuid = UuidUtil.createShort();
        Draw draw = new Draw();
        draw.setGenerateSize(createImageDto.getSize());
        draw.setGenerateQuality(createImageDto.getQuality());
        draw.setGenerateNumber(generateNumber);
        draw.setUuid(uuid);
        draw.setAiModelId(aiModel.getId());
        draw.setAiModelName(createImageDto.getModelName());
        draw.setUserId(user.getId());
        draw.setInteractingMethod(createImageDto.getInteractingMethod());
        draw.setProcessStatus(STATUS_DOING);
        draw.setPrompt(createImageDto.getPrompt());
        draw.setOriginalImage(createImageDto.getOriginalImage());
        draw.setMaskImage(createImageDto.getMaskImage());
        getBaseMapper().insert(draw);
        Draw obj = this.lambdaQuery().eq(Draw::getUuid, uuid).one();
        self.createFromRemote(obj, user);
        return uuid;
    }

    /**
     * Regenerate the image that was fail
     *
     * @param uuid
     */
    public void regenerate(String uuid) {
        User user = ThreadContext.getCurrentUser();
        Draw obj = this.lambdaQuery()
                .eq(Draw::getUuid, uuid)
                .eq(Draw::getProcessStatus, STATUS_FAIL)
                .oneOpt().orElseThrow(() -> new BaseException(B_FIND_IMAGE_404));

        self.createFromRemote(obj, user);
    }

    /**
     * 异步生成图片
     *
     * @param draw
     * @param user
     */
    @Async("imagesExecutor")
    public void createFromRemote(Draw draw, User user) {
        String drawingKey = MessageFormat.format(RedisKeyConstant.USER_DRAWING, user.getId());
        stringRedisTemplate.opsForValue().set(drawingKey, "1", 30, TimeUnit.SECONDS);

        try {
            //Increase the number of the request
            String requestTimesKey = MessageFormat.format(RedisKeyConstant.USER_REQUEST_TEXT_TIMES, user.getId());
            rateLimitHelper.increaseRequestTimes(requestTimesKey, LocalCache.IMAGE_RATE_LIMIT_CONFIG);

            AbstractImageModelService<T> imageModelService = ImageModelContext.getModelService(draw.getAiModelName());
            List<String> images = new ArrayList<>();
            if (draw.getInteractingMethod() == INTERACTING_METHOD_GENERATE_IMAGE) {
                images = imageModelService.generateImage(user, draw);
            } else if (draw.getInteractingMethod() == INTERACTING_METHOD_EDIT_IMAGE) {
                images = imageModelService.editImage(user, draw);
            } else if (draw.getInteractingMethod() == INTERACTING_METHOD_VARIATION) {
                images = imageModelService.createImageVariation(user, draw);
            }
            List<String> imageUuids = new ArrayList<>();
            images.forEach(imageUrl -> {
                String imageUuid = fileService.saveImageToLocal(user, imageUrl);
                imageUuids.add(imageUuid);
            });
            String imageUuidsJoin = String.join(",", imageUuids);
            if (StringUtils.isBlank(imageUuidsJoin)) {
                self.lambdaUpdate().eq(Draw::getId, draw.getId()).set(Draw::getProcessStatus, STATUS_FAIL).update();
                return;
            }
            String respImagesPath = String.join(",", images);
            updateDrawSuccess(draw.getId(), respImagesPath, imageUuidsJoin);

            //Update the cost of current user
            boolean tokenIsFree = imageModelService.getAiModel().getIsFree();
            UserDayCost userDayCost = userDayCostService.getTodayCost(user, tokenIsFree);
            UserDayCost saveOrUpdateInst = new UserDayCost();
            if (null == userDayCost) {
                saveOrUpdateInst.setUserId(user.getId());
                saveOrUpdateInst.setDay(LocalDateTimeUtil.getToday());
                saveOrUpdateInst.setImagesNumber(images.size());
            } else {
                saveOrUpdateInst.setId(userDayCost.getId());
                saveOrUpdateInst.setImagesNumber(userDayCost.getImagesNumber() + images.size());
            }
            saveOrUpdateInst.setIsFree(tokenIsFree);
            userDayCostService.saveOrUpdate(saveOrUpdateInst);
        } catch (BaseException e) {
            log.error("createFromRemote error", e);
            updateDrawFail(draw.getId(), e.getMessage());
        } finally {
            stringRedisTemplate.delete(drawingKey);
        }
    }

    public void updateDrawSuccess(Long drawId, String respImagesPath, String localImagesUuid) {
        Draw updateImage = new Draw();
        updateImage.setId(drawId);
        updateImage.setRespImagesPath(respImagesPath);
        updateImage.setGeneratedImages(localImagesUuid);
        updateImage.setProcessStatus(STATUS_SUCCESS);
        getBaseMapper().updateById(updateImage);

        if (StringUtils.isBlank(localImagesUuid)) {
            return;
        }
        AdiFile adiFile = fileService.lambdaQuery().eq(AdiFile::getUuid, localImagesUuid).oneOpt().orElse(null);
        if (null != adiFile) {
            fileService.lambdaUpdate().eq(AdiFile::getId, adiFile.getId()).set(AdiFile::getRefCount, adiFile.getRefCount() + 1).update();
        }
    }

    public void updateDrawFail(Long drawId, String failMsg) {
        Draw updateImage = new Draw();
        updateImage.setId(drawId);
        updateImage.setProcessStatus(STATUS_FAIL);
        updateImage.setProcessStatusRemark(failMsg);
        getBaseMapper().updateById(updateImage);
    }

    public DrawListResp listByCurrentUser(Long maxId, int pageSize) {
        List<Draw> list = this.lambdaQuery()
                .eq(Draw::getUserId, ThreadContext.getCurrentUserId())
                .eq(Draw::getIsDeleted, false)
                .lt(Draw::getId, maxId)
                .orderByDesc(Draw::getId)
                .last("limit " + pageSize)
                .list();
        list.sort(Comparator.comparing(Draw::getId));
        DrawListResp listResp = drawsToListResp(list);
        listResp.getDraws().forEach(item -> item.setIsStar(drawStarService.isStarred(item.getId(), ThreadContext.getCurrentUserId())));
        return listResp;
    }

    /**
     * 倒序查询公开的图片
     *
     * @param maxId    最大的ID
     * @param pageSize 每次请示获取的数量
     * @return 图片列表
     */
    public DrawListResp listPublic(Long maxId, int pageSize) {
        List<Draw> list = this.lambdaQuery()
                .eq(Draw::getIsDeleted, false)
                .eq(Draw::getIsPublic, true)
                .lt(Draw::getId, maxId)
                .orderByDesc(Draw::getId)
                .last("limit " + pageSize)
                .list();
        DrawListResp listResp = drawsToListResp(list);
        if (StringUtils.isNotBlank(ThreadContext.getToken())) {
            listResp.getDraws().forEach(item -> item.setIsStar(drawStarService.isStarred(item.getId(), ThreadContext.getCurrentUserId())));
        }
        return listResp;
    }

    public DrawListResp listStarred(Long maxId, int pageSize) {
        List<DrawStar> stars = drawStarService.listByCurrentUser(maxId, pageSize);
        if (CollectionUtils.isEmpty(stars)) {
            DrawListResp resp = new DrawListResp();
            resp.setDraws(Collections.emptyList());
            resp.setMinId(Long.MAX_VALUE);
            return resp;
        }
        List<Draw> list = this.lambdaQuery()
                .in(Draw::getId, stars.stream().map(DrawStar::getDrawId).toList())
                .list();
        DrawListResp listResp = drawsToListResp(list);
        listResp.getDraws().forEach(item -> item.setIsStar(true));
        return listResp;
    }

    private DrawListResp drawsToListResp(List<Draw> draws) {
        List<DrawDto> dtoList = new ArrayList<>();
        draws.forEach(item -> dtoList.add(convertDrawToDto(item)));
        DrawListResp result = new DrawListResp();
        result.setDraws(dtoList);
        result.setMinId(draws.stream().map(Draw::getId).reduce(Long.MAX_VALUE, Long::min));
        return result;
    }

    public DrawDto getOne(String uuid) {
        Draw draw = this.lambdaQuery()
                .eq(Draw::getUuid, uuid)
                .eq(Draw::getUserId, ThreadContext.getCurrentUserId())
                .oneOpt()
                .orElse(null);
        if (null != draw) {
            return convertDrawToDto(draw);
        } else {
            return null;
        }
    }

    public DrawDto getOrThrow(String uuid) {
        Draw draw = this.lambdaQuery()
                .eq(Draw::getUuid, uuid)
                .eq(Draw::getUserId, ThreadContext.getCurrentUserId())
                .one();
        if (null == draw) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        return convertDrawToDto(draw);
    }

    public DrawDto getPublicOne(String uuid) {
        Draw draw = this.lambdaQuery()
                .eq(Draw::getUuid, uuid)
                .eq(Draw::getIsDeleted, false)
                .oneOpt()
                .orElse(null);
        //公开的图片或者自己的图片，都可以获取到
        if (
                null != draw
                        && (draw.getIsPublic() || (StringUtils.isNotBlank(ThreadContext.getToken()) && ThreadContext.getCurrentUserId().equals(draw.getUserId())))
        ) {
            return convertDrawToDto(draw);
        } else {
            return null;
        }
    }

    /**
     * 删除做图记录
     *
     * @param uuid 绘图任务uuid
     * @return 是否删除成功
     */
    public boolean del(String uuid) {
        Draw draw = checkAndGet(uuid);
        if (StringUtils.isNotBlank(draw.getGeneratedImages())) {
            String[] uuids = draw.getGeneratedImages().split(",");
            for (String fileUuid : uuids) {
                fileService.removeFileAndSoftDel(fileUuid);
            }
        }
        self.softDel(draw.getId());
        return true;
    }

    /**
     * 删除做图任务中的一张图片
     *
     * @param uuid               adi_draw uuid
     * @param generatedImageUuid 图片uuid
     * @return 是否成功
     */
    public boolean delGeneratedFile(String uuid, String generatedImageUuid) {
        Draw draw = checkAndGet(uuid);
        if (StringUtils.isBlank(draw.getGeneratedImages())) {
            return false;
        }
        String[] uuids = draw.getGeneratedImages().split(",");
        for (int i = 0; i < uuids.length; i++) {
            String fileUuid = uuids[i];
            if (fileUuid.equals(generatedImageUuid)) {
                fileService.removeFileAndSoftDel(fileUuid);
                uuids[i] = "";
            }
        }
        String remainFiles = Arrays.stream(uuids)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(","));
        self.lambdaUpdate().eq(Draw::getId, draw.getId()).set(Draw::getGeneratedImages, remainFiles).update();
        return true;
    }

    private DrawDto convertDrawToDto(Draw draw) {
        DrawDto dto = new DrawDto();
        BeanUtils.copyProperties(draw, dto);

        //Image uuid string to uuid list
        List<String> images = new ArrayList<>();
        if (StringUtils.isNotBlank(dto.getGeneratedImages())) {
            String[] imageUuids = dto.getGeneratedImages().split(",");
            images.addAll(Arrays.asList(imageUuids));
        }
        dto.setImageUuids(images);

        if (StringUtils.isNotBlank(draw.getOriginalImage())) {
            dto.setOriginalImageUuid(draw.getOriginalImage());
        }
        if (StringUtils.isNotBlank(draw.getMaskImage())) {
            dto.setMaskImageUuid(draw.getMaskImage());
        }
        boolean isStarred = drawStarService.isStarred(draw.getId(), dto.getUserId());
        dto.setIsStar(isStarred);
        //User
        User user = userService.getByUserId(dto.getUserId());
        if (null != user) {
            dto.setUserUuid(user.getUuid());
            dto.setUserName(user.getName());
        }
        return dto;
    }

    private Draw checkAndGet(String uuid) {
        Draw draw;
        if (Boolean.TRUE.equals(ThreadContext.getCurrentUser().getIsAdmin())) {
            draw = this.lambdaQuery()
                    .eq(Draw::getUuid, uuid)
                    .eq(Draw::getIsDeleted, false)
                    .oneOpt()
                    .orElse(null);
        } else {
            draw = this.lambdaQuery()
                    .eq(Draw::getUuid, uuid)
                    .eq(Draw::getUserId, ThreadContext.getCurrentUserId())
                    .eq(Draw::getIsDeleted, false)
                    .oneOpt()
                    .orElse(null);
        }
        if (null == draw) {
            throw new BaseException(A_AI_IMAGE_NOT_FOUND);
        }
        return draw;
    }

    private void softDel(Long id) {
        this.lambdaUpdate().eq(Draw::getId, id).set(Draw::getIsDeleted, true).update();
    }

    public int sumTodayCost() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), 0, 0);
        LocalDateTime end = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), 23, 59, 59);
        return this.lambdaQuery()
                .between(Draw::getCreateTime, begin, end)
                .eq(Draw::getIsDeleted, false)
                .count()
                .intValue();
    }

    public int sumCurrMonthCost() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = LocalDateTime.of(now.getYear(), now.getMonth(), 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(now.getYear(), now.getMonth(), 1, 23, 59, 59).plusMonths(1).minusDays(1);
        return this.lambdaQuery()
                .between(Draw::getCreateTime, begin, end)
                .eq(Draw::getIsDeleted, false)
                .count()
                .intValue();
    }

    public void setDrawPublic(String uuid, Boolean isPublic, Boolean withWatermark) {
        Draw draw = checkAndGet(uuid);
        //生成水印
        if (BooleanUtils.isTrue(withWatermark)) {
            AdiFile adiFile = fileService.getFile(uuid);
            String markImagePath = fileService.getWatermarkImagesPath(adiFile);
            if (!FileUtil.exist(markImagePath)) {
                Img.from(FileUtil.file(adiFile.getPath())).setPositionBaseCentre(false).pressText(
                        ThreadContext.getCurrentUser().getName() + "|" + adiHost, Color.WHITE,
                        null,
                        0,
                        0,
                        0.4f);
            }
        }
        this.lambdaUpdate()
                .eq(Draw::getId, draw.getId())
                .set(Draw::getIsPublic, isPublic)
                .set(BooleanUtils.isTrue(withWatermark), Draw::getWithWatermark, withWatermark)
                .update();
    }

    public DrawDto toggleStar(String uuid) {
        DrawDto draw = getOrThrow(uuid);
        drawStarService.toggle(draw.getId(), ThreadContext.getCurrentUserId());

        //Calculate stars
        boolean starred = drawStarService.isStarred(draw.getId(), ThreadContext.getCurrentUserId());
        int stars = draw.getStarCount() + (starred ? 1 : -1);
        this.lambdaUpdate()
                .eq(Draw::getId, draw.getId())
                .set(Draw::getStarCount, stars)
                .update();
        draw.setStarCount(stars);
        draw.setIsStar(starred);
        return draw;
    }
}
