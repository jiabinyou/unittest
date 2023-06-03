/* Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved. */

package com.amazonaws.ucbuzzccp.helper;

import com.amazonaws.ucbuzz.meetings.sdc.MeetingsDynamicConfigProvider;
import com.amazonaws.ucbuzzccp.common.PasscodeInfo;
import com.amazonaws.ucbuzzccp.dao.WaitingRoomAccessRequestDao;
import com.amazonaws.ucbuzzccp.dao.model.Pin;
import com.amazonaws.ucbuzzccp.dao.model.PinType;
import com.amazonaws.ucbuzzccp.dao.model.WaitingRoomAccessRequest;
import com.amazonaws.ucbuzzccp.handler.ExceptionHandler;
import com.amazonaws.ucbuzzccp.handler.PinHandler;
import com.amazonaws.ucbuzzccp.v2.ForbiddenException;
import com.amazonaws.ucbuzzccp.v2.NotFoundException;
import com.google.common.annotations.VisibleForTesting;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
public class V3MeetingExperienceHelper {
    @Autowired MeetingsDynamicConfigProvider meetingsConfig;
    @Autowired protected PinHandler pinHandler;
    @Autowired protected WaitingRoomAccessRequestDao waitingRoomAccessRequestDao;
    @Autowired protected PasscodeParseHelper passcodeParseHelper;

    public static final String V3MEETINGEXPERIENCE_SDC_CONFIG_KEY = "V3MeetingExperience";

    @VisibleForTesting public boolean isPinOwnerAllowlistedForV3Expereince(@NonNull String profileId) {
        return meetingsConfig.isFeatureOnFor(V3MEETINGEXPERIENCE_SDC_CONFIG_KEY, profileId);
    }

    public void  isPinAllowlisted(Pin pin) {
        isPinAllowlisted(pin, false);
    }

    public void isPinAllowlisted(Pin pin, boolean throwV2) {
        if (pin.getBelongsToEntityId() == null ||
                !isPinOwnerAllowlistedForV3Expereince(pin.getBelongsToEntityId())) {
            if(throwV2){
                throw new ForbiddenException("pin owner not allowlisted");
            }
            throw ExceptionHandler.newForbiddenException("pin owner not allowlisted", null);
        }
    }

    public Pin findPin(String code) {
        passcodeParseHelper.parseAndVerifyPasscode(code, false);
        return findPin(code, false);
    }

    public Pin findConferencePin(String code) {
        PasscodeInfo info = passcodeParseHelper.parseAndVerifyPasscode(code, false);
        return findPin(info.getConferencePin(), false);
    }

    public Pin findAttendeePin(String code) {
        PasscodeInfo info = passcodeParseHelper.parseAndVerifyPasscode(code, true);
        if (info.hasPersonalizedPin()) {
            return info.getAttendeePin();
        } else {
            throw ExceptionHandler.newNotFoundExceptionWithCode("Attendee pin has invalid length "
                + code.length(), "invalid_passcode");
        }
    }

    public Pin findPin(String code, boolean throwV2) {
        Pin pin = pinHandler.findPin(code, throwV2);
        if (pin == null || pin.isExpired() || pin.getPinType() == PinType.Sdk) {
            if(throwV2){
                throw new NotFoundException(String.format("Could not find pin: %s", pin));
            }
            throw ExceptionHandler.newNotFoundExceptionWithCode(String.format("Could not find pin: %s", pin),
                    "invalid_passcode");
        }
        return pin;
    }

    /**
     * Check whether waitingRoomAccessRequest exists by accessRequestId
     * If not or waitingRoomAccessRequest is not allowed, throw BadRequest Exception
     * @param accessRequestId
     * @return WaitingRoomAccessRequest object if successful
     */
    public WaitingRoomAccessRequest validateWaitingRoomAccessRequest(String accessRequestId) {
        if (StringUtils.isEmpty(accessRequestId)) {
            throw ExceptionHandler.newBadRequestException(
                    String.format("null or empty accessRequestId:%s", accessRequestId));
        }

        WaitingRoomAccessRequest waitingRoomAccessRequest =
                waitingRoomAccessRequestDao.load(accessRequestId);
        log.info("Load waitingRoomAccessRequest: {}", waitingRoomAccessRequest);

        if (waitingRoomAccessRequest == null) {
            throw ExceptionHandler.newBadRequestException(
                    String.format("Invalid accessRequestId:%s", accessRequestId));
        }

        if (!waitingRoomAccessRequest.isAllowed()) {
            throw new ForbiddenException(String.format("Not allowed status:%s",
                    waitingRoomAccessRequest.getStatus()));
        }

        return waitingRoomAccessRequest;
    }
}
