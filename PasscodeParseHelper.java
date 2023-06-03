/* Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved. */

package com.amazonaws.ucbuzzccp.helper;

import com.amazon.metrics.declarative.MetricsManager;
import com.amazonaws.ucbuzzccp.LimitExceededException;
import com.amazonaws.ucbuzzccp.NotFoundException;
import com.amazonaws.ucbuzzccp.common.PasscodeInfo;
import com.amazonaws.ucbuzzccp.common.pin.PinManager;
import com.amazonaws.ucbuzzccp.dao.WaitingRoomAccessRequestDao;
import com.amazonaws.ucbuzzccp.dao.model.Pin;
import com.amazonaws.ucbuzzccp.dao.model.PinType;
import com.amazonaws.ucbuzzccp.dao.model.WaitingRoomAccessRequest;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;

import javax.inject.Inject;
import javax.measure.unit.Unit;

import static com.amazonaws.ucbuzzccp.common.log.SecurityTagger.tag;
import static com.amazonaws.ucbuzzccp.handler.ExceptionHandler.newNotFoundExceptionWithCode;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;

@Slf4j
public class PasscodeParseHelper {

    @Autowired
    protected PinManager pinManager;
    @Autowired
    protected MetricsManager metricsManager;
    @Autowired
    protected WaitingRoomAccessRequestDao waitingRoomAccessRequestDao;

    private SecureRandom random;
    public static final int CONFERENCE_PASSCODE_LENGTH = 10;
    public static final int ATTENDEE_PASSCODE_LENGTH = 13;
    public static final int DIAL_IN_PASSCODE_LENGTH = 15;
    private static final int GENERATE_UNIQUE_PIN_TRIES_MAX_LIMIT = 10;
    private static final int USER_PIN_LENGTH = 5; // pin space is only 90000 not 100000 for pin policy of 5

    @Inject
    public PasscodeParseHelper() {
        try {
            random = SecureRandom.getInstance("SHA1PRNG", "SUN");
            random.nextBytes(new byte[128]);
        } catch(NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new NotFoundException("Failed to find SecureRandom instance for SHA1PRNG algo from SUN.", e);
        }
    }

    /**
     * Parse passcode and validate both conference and personalized attendee parts (if latter is present).
     * This is an optimization to avoid multiple pin queries to Pins table in separate methods.
     *
     * @param requestPasscode passcode to parse and validate
     * @return PasscodeInfo object
     * <p>
     * TODO: we are using the legacy rules right now for resolving this stuff where a conference pin is 10 digits
     * with an optional 3 digit personalized extension
     */

    public PasscodeInfo parseAndVerifyPasscode(String requestPasscode) {
        return parseAndVerifyPasscode(requestPasscode, true);
    }

    public PasscodeInfo parseAndVerifyPasscode(String requestPasscode, boolean loadAttendeePin) {
        int passcodeLength = requestPasscode.length();
        if (passcodeLength < CONFERENCE_PASSCODE_LENGTH) {
            throw newNotFoundExceptionWithCode("Pin has invalid length " + passcodeLength, "invalid_passcode");
        }

        PasscodeInfo info = new PasscodeInfo();
        info.setConferencePin(requestPasscode.substring(0, CONFERENCE_PASSCODE_LENGTH));

        if (passcodeLength == ATTENDEE_PASSCODE_LENGTH && loadAttendeePin) {
            String attendeePin = requestPasscode;
            Pin attendeePinModel = pinManager.find(attendeePin);
            if (attendeePinModel == null) {
                throw newNotFoundExceptionWithCode("Could not find pin", "invalid_passcode");
            }
            if (attendeePinModel.getPinType() != PinType.Attendee) {
                throw newNotFoundExceptionWithCode(
                        String.format("Invalid type %s for pin:%s", attendeePinModel.getType(), tag(requestPasscode)),
                        "invalid_passcode");
            }
            info.setAttendeePin(attendeePinModel);
            metricsManager.get().addCount("JoinedByPinType:Attendee", 1, Unit.ONE);
        } else if (passcodeLength == DIAL_IN_PASSCODE_LENGTH) {
            info.setDialInPasscode(requestPasscode);
            metricsManager.get().addCount("JoinedByPinType:DialIn", 1, Unit.ONE);
        } else if (passcodeLength > CONFERENCE_PASSCODE_LENGTH) {
            throw newNotFoundExceptionWithCode(String.format("Invalid passcode %s with length %s",
                    requestPasscode, requestPasscode.length()), "invalid_passcode");
        }

        return info;
    }

    public String generateUserDialInCode(@NonNull String wrId, @NonNull String profileId, @NonNull String meetingPin) {
        log.info("generate WR user dial-in code for waitingRoomId: {}, profileId: {}", wrId, profileId);
        String code = generateRandomCode(meetingPin, wrId, 0);
        log.info("Generated UserDialInCode: {} for waitingRoomId: {}, profileId: {}", code, wrId, profileId);
        return code;
    }

    private String generateRandomCode(@NonNull String baseCode, @NonNull String wrId, int tryCount) {
        if (tryCount == GENERATE_UNIQUE_PIN_TRIES_MAX_LIMIT) {
            log.info("Max tries reached: Cannot make more than {} tries to allocate waiting room user dial-in code",
                GENERATE_UNIQUE_PIN_TRIES_MAX_LIMIT);
            metricsManager.get().addCount("WRUserDialInCode: MaxTriesReached", 1, Unit.ONE);
            throw new LimitExceededException("Max tries reached for allocating waiting room user dial-in code");
        }
        String code = String.valueOf((long) Math.floor(random.nextDouble()
            * (9 * (long) Math.pow(10, USER_PIN_LENGTH - 1))) + (long)Math.pow(10, USER_PIN_LENGTH - 1));
        code = baseCode + code;
        if (checkForCollisions(code, wrId)) {
            return generateRandomCode(baseCode, wrId, tryCount+1);
        }
        log.info("No. of tries made for allocating waiting room user dial-in code: {}", String.valueOf(tryCount+1));
        metricsManager.get().addCount("WRUserDialInCode: AllocationTries", tryCount+1, Unit.ONE);
        return code;
    }

    boolean checkForCollisions(@NonNull String userDialInCode, @NonNull String wrId) {
        WaitingRoomAccessRequest wrAccessRequest = waitingRoomAccessRequestDao
            .loadWRAccessRequestByDialInCodeAndWrId(userDialInCode, wrId);
        if (wrAccessRequest != null) {
            return true;
        }
        return false;
    }
}
