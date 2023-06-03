/* Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved. */

package com.amazonaws.ucbuzzccp.handler;

import com.amazonaws.ucbuzzccp.AccessStatus;
import com.amazonaws.ucbuzzccp.common.waitingRoom.WaitingRoomAccessRequestDecision;
import com.amazonaws.ucbuzzccp.dao.WaitingRoomAccessRequestDao;
import com.amazonaws.ucbuzzccp.dao.model.Pin;
import com.amazonaws.ucbuzzccp.dao.model.WaitingRoom;
import com.amazonaws.ucbuzzccp.dao.model.WaitingRoomAccessLevel;
import com.amazonaws.ucbuzzccp.dao.model.WaitingRoomAccessRequest;
import com.amazonaws.ucbuzzccp.helper.V3MeetingExperienceHelper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import java.util.Date;
import java.util.UUID;

@Slf4j
public class AnonymousSessionV3Handler {
    @Autowired
    WaitingRoomAccessRequestDao waitingRoomAccessRequestDao;

    @Autowired protected WaitingRoomHandler waitingRoomHandler;
    @Autowired protected AttendeeAuthorizeHandler attendeeAuthorizeHandler;
    @Autowired V3MeetingExperienceHelper v3MeetingExperienceHelper;
    private Pin pinModel;

    public WaitingRoomAccessRequest insertAccessRequestIntoWaitingRoom(String passcode,
            com.amazonaws.ucbuzzccp.dao.model.Conference conference,
            String profileId, String deviceId, String devicePlatform, String displayName, String awsAccountId) {
        Pin pin = v3MeetingExperienceHelper.findConferencePin(passcode);
        String organizerProfileId = pinModel.getProfileId();
        boolean isV3MeetingsExperienceEnabled = v3MeetingExperienceHelper.isPinOwnerAllowlistedForV3Expereince(
                organizerProfileId);
        if (isV3MeetingsExperienceEnabled) {
            WaitingRoom waitingRoom = waitingRoomHandler.getOrCreateWaitingRoom(pin, conference);
            String uuid = UUID.randomUUID().toString();
            Date requestedAt = new Date();
            WaitingRoomAccessRequestDecision decisionCode =
                    WaitingRoomAccessRequestDecision.DECISION_CODE_WR_REQUEST_SIGV4_AUTHENTICATED;
            String resolvedDecision = decisionCode.toString();
            log.info("Inserting the details into access request table for profile id {}", profileId);
            WaitingRoomAccessRequest accessRequest = WaitingRoomAccessRequest.builder()
                    .accessRequestId(uuid)
                    .waitingRoomId(waitingRoom.getId())
                    .partitionNum(waitingRoom.getPartitionNum())
                    .profileId(profileId)
                    .deviceId(deviceId)
                    .devicePlatform(devicePlatform)
                    .displayName(displayName)
                    .isAnonymous(true)
                    .status(WaitingRoomAccessLevel.fromString(AccessStatus.APPROVED))
                    .requestedAt(requestedAt)
                    .isModerator(false)
                    .callerAwsAccount(awsAccountId)
                    .shouldExpireAt(null)
                    .resolvedReason(resolvedDecision)
                    .build();
            waitingRoomAccessRequestDao.insert(accessRequest);
            return accessRequest;
        }
        return new WaitingRoomAccessRequest();
    }
}
