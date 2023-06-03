/* Copyright 2016 Amazon.com, Inc. or its affiliates. All Rights Reserved. */
package com.amazonaws.ucbuzzccp.handler;

import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.ucbuzzprofileservice.model.Profile;
import com.amazonaws.ucbuzz.meetings.sdc.MeetingsDynamicConfigProvider;
import com.amazonaws.ucbuzzccp.BadRequestException;
import com.amazonaws.ucbuzzccp.CreatePinRequest;
import com.amazonaws.ucbuzzccp.CreatePinResponse;
import com.amazonaws.ucbuzzccp.FindPinResponse;
import com.amazonaws.ucbuzzccp.NotFoundException;
import com.amazonaws.ucbuzzccp.PinEntity;
import com.amazonaws.ucbuzzccp.PinResult;
import com.amazonaws.ucbuzzccp.PinResultFailure;
import com.amazonaws.ucbuzzccp.ReclaimPinRequest;
import com.amazonaws.ucbuzzccp.RecreatePinRequest;
import com.amazonaws.ucbuzzccp.UnprocessableEntityException;
import com.amazonaws.ucbuzzccp.common.error.CCPError;
import com.amazonaws.ucbuzzccp.common.identity.CCPIdentityClient;
import com.amazonaws.ucbuzzccp.common.identity.ProfileNotFoundException;
import com.amazonaws.ucbuzzccp.common.pin.IllegalReclaimPinRequest;
import com.amazonaws.ucbuzzccp.common.pin.PinManager;
import com.amazonaws.ucbuzzccp.common.pin.PinPolicy;
import com.amazonaws.ucbuzzccp.dao.PinAliasDao;
import com.amazonaws.ucbuzzccp.dao.PinDao;
import com.amazonaws.ucbuzzccp.dao.PinNotFoundException;
import com.amazonaws.ucbuzzccp.dao.model.Pin;
import com.amazonaws.ucbuzzccp.dao.model.PinAlias;
import com.amazonaws.ucbuzzccp.dao.model.PinType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.Validate;

import javax.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
public class PinHandler {
    public static final String SDC_REQUIRE_DEACTIVATE_ON = "RequireDeactivateOn";

    private static List<PinResultFailure> EMPTY_FAILURES_LIST = new ArrayList<>();
    @VisibleForTesting
    static final long DEACTIVATE_ON_THRESHOLD_DAYS = 365;

    private final CCPIdentityClient identityClient;
    private final PinManager pinManager;
    private final PinAliasHandler pinAliasHandler;
    private final PinAliasDao pinAliasDao;
    private final PinDao pinDao;
    private final MeetingsDynamicConfigProvider meetingsConfig;

    @Inject
    public PinHandler(CCPIdentityClient identityClient, PinManager pinManager, PinAliasHandler pinAliasHandler,
            PinAliasDao pinAliasDao, PinDao pinDao, MeetingsDynamicConfigProvider meetingsConfig) {
        this.identityClient = identityClient;
        this.pinManager = pinManager;
        this.pinAliasHandler = pinAliasHandler;
        this.pinAliasDao = pinAliasDao;
        this.pinDao = pinDao;
        this.meetingsConfig = meetingsConfig;
    }

    private static PinPolicy createPinPolicyFromRequest(com.amazonaws.ucbuzzccp.PinPolicy policy) {
        PinPolicy p = new PinPolicy(PinPolicy.DEFAULT);

        if (policy == null) {
            return p;
        }

        if (policy.getBasePin() != null) {
            p.setBasePin(policy.getBasePin());
        }

        if (policy.getMinLength() != null) {
            p.setMinLength(policy.getMinLength());
        }

        if (policy.getMaxLength() != null) {
            p.setMaxLength(policy.getMaxLength());
        }

        if (policy.getPrefixLength() != null) {
            p.setPrefixLength(policy.getPrefixLength());
        }

        if (policy.getSuffixLength() != null) {
            p.setSuffixLength(policy.getSuffixLength());
        }

        if (policy.getDeactivateOn() != null) {
            p.setDeactivateOn(policy.getDeactivateOn());
        }

        if (policy.getReservedBy() != null) {
            p.setReservedBy(policy.getReservedBy());
        }

        return p;
    }

    public void expire(String pin) {
        pinManager.expire(pin);
    }

    public CreatePinResponse create(@NonNull CreatePinRequest request) {
        PinPolicy policy  = createPinPolicyFromRequest(request.getPinPolicy());
        PinType type = PinType.findByName(request.getPinType());
        if (type == null) {
            throw new BadRequestException("Invalid pin type " + request.getPinType());
        }
        switch (type) {
        case Generic:
            return handleGenericPinCreate(policy, request);
        case Personal:
            return handlePersonalPinCreate(policy, request);
        case Conference:
            return handleConferencePinCreate(policy, request);
        case Attendee:
            return handleAttendeePinCreate(policy, request);
        default:
            throw new BadRequestException("Unhandled pin type " + request.getPinType());
        }
    }

    public Pin findPin(String code) {
        return findPin(code, false);
    }

    public Pin findPin(String code, boolean throwV2) {
        String resolvedPasscode = pinAliasHandler.resolveToPasscode(code);
        Pin pin = pinManager.find(resolvedPasscode);
        if (pin == null) {
            if(throwV2){
                throw new com.amazonaws.ucbuzzccp.v2.NotFoundException(
                        String.format("Could not find pin: %s", code));
            }
            throw new NotFoundException("Unable to locate pin: " + resolvedPasscode);
        }
        return pin;
    }

    public Pin findConferencePin(String code) {
        return findConferencePin(code, false);
    }

    public Pin findConferencePin(String code, boolean throwV2) {
        String resolvedPasscode = pinAliasHandler.resolveToPasscode(code);
        Pin pin = pinManager.find(resolvedPasscode);
        if (pin == null) {
            if(throwV2){
                throw new com.amazonaws.ucbuzzccp.v2.NotFoundException(
                        String.format("Could not find pin: %s", code));
            }
            throw new NotFoundException("Unable to locate pin: " + resolvedPasscode);
        }
        return pin;
    }

    public FindPinResponse find(String code) {
        String resolvedPasscode = pinAliasHandler.resolveToPasscode(code);
        Pin pin = pinManager.find(resolvedPasscode);
        if (pin == null) {
            throw new NotFoundException("Unable to locate pin: " + resolvedPasscode);
        } else {
            return FindPinResponse.builder()
                .withPinResult(getPinResult(pin.getEntityId(), null, pin.getCode()))
                .build();
        }
    }

    private Profile resolveProfile(PinEntity e) {
        if (e.getEntityId() == null && e.getEmail() == null) {
            throw new BadRequestException("Not enough PinEntity information to resolve profile id ");
        }
        return resolveOrCreateProfile(Optional.ofNullable(e.getEntityId()), Optional.ofNullable(e.getEmail()));
    }

    private PinResult getPinResult(String entityId, String email, String pin) {
        return PinResult.builder()
            .withEntityId(entityId)
            .withEmail(email)
            .withPin(pin)
            .build();
    }

    private static CreatePinResponse createPinResponse(List<PinResult> successes, List<PinResultFailure> failures) {
        return CreatePinResponse.builder()
            .withAllocatedPins(successes)
            .withPinResultFailure(failures)
            .build();
    }

    private CreatePinResponse handleGenericPinCreate(PinPolicy policy, CreatePinRequest request) {
        throw new BadRequestException("Generic pin create currently unsupported");
    }

    private void ensureOneEntity(CreatePinRequest request) {
        if (request.getEntities() == null) {
            throw new BadRequestException(
                String.format("%s pin create: you must provide one entity", request.getPinType()));
        }
        if (request.getEntities().size() != 1) {
            throw new BadRequestException(
                String.format("%s pin create currently supports one entity at a time and you provided %d",
                    request.getPinType(), request.getEntities().size()));
        }
    }

    private CreatePinResponse handlePersonalPinCreate(PinPolicy policy, CreatePinRequest request) {
        ensureOneEntity(request);

        List<PinEntity> entities = request.getEntities();
        Profile profile = resolveProfile(entities.get(0));
        String pin = pinManager.findOrCreatePersonalPin(profile).getCode();

        return createPinResponse(
                Collections.singletonList(getPinResult(profile.getProfileId(), entities.get(0).getEmail(), pin)),
            EMPTY_FAILURES_LIST);
    }

    private CreatePinResponse handleConferencePinCreate(PinPolicy policy, CreatePinRequest request) {
        validateConferencePinCreate(request, policy);

        List<PinEntity> entities = request.getEntities();
        Profile profile = resolveProfile(entities.get(0));
        String pin = pinManager.generate(PinType.Conference, profile.getProfileId(), policy);
        if (!StringUtils.isEmpty(request.getModeratorCode())) {
            pinManager.updateModeratorInfo(pin, request.getModeratorCode());
        }
        return createPinResponse(
                Collections.singletonList(getPinResult(profile.getProfileId(), entities.get(0).getEmail(), pin)),
            EMPTY_FAILURES_LIST);
    }

    private void validateConferencePinCreate(CreatePinRequest request, PinPolicy policy) {
        // https://i.amazon.com/Chime-59916 reserved pins are managed
        // externally, don't require deactivation
        if (StringUtils.isEmpty(policy.getReservedBy())) {
            if (isDeactivateOnAbsent(policy)) {
                throw new BadRequestException("DeactivateOn absent for conference pin create");
            }
            if (isDeactivateOnInvalid(policy)) {
                throw new BadRequestException("Invalid DeactivateOn" + policy.getDeactivateOn().toString());
            }
        }
        ensureOneEntity(request);
    }

    private boolean isDeactivateOnAbsent(PinPolicy policy) {
        return meetingsConfig.getBoolean(SDC_REQUIRE_DEACTIVATE_ON, false) &&
                (policy == null || policy.getDeactivateOn() == null);
    }

    private boolean isDeactivateOnInvalid(PinPolicy policy) {
        Instant minimumDeactivateOn = Instant.now().plus(DEACTIVATE_ON_THRESHOLD_DAYS, ChronoUnit.DAYS);
        return policy != null && policy.getDeactivateOn() != null &&
                policy.getDeactivateOn().toInstant().isAfter(minimumDeactivateOn);
    }

    private CreatePinResponse handleAttendeePinCreate(PinPolicy policy, CreatePinRequest request) {
        List<PinEntity> entities = request.getEntities();
        if (entities.size() == 0) {
            throw new BadRequestException("Attendee pin create requires a list of pin entities.");
         }

        if (policy.getBasePin() == null) {
            throw new BadRequestException("Attendee pin create requires a base pin.");
        }
        String passcode = pinAliasHandler.resolveToPasscode(policy.getBasePin());
        Pin findResult = pinManager.find(passcode);
        if (findResult == null) {
            throw new BadRequestException("Attendee pin create provided non-existent base pin.");
        }

        // TODO: this must come from the organization's pin policy
        policy.setMinLength(3);
        policy.setMaxLength(3);

        List<PinResult> pinResults = new ArrayList<>();
        for (PinEntity pe: entities) {
            Profile profile = resolveProfile(pe);
            String pin  = pinManager.generate(PinType.Attendee, profile.getProfileId(), policy);
            pinResults.add(getPinResult(profile.getProfileId(), pe.getEmail(), pin));
        }

        return createPinResponse(pinResults, EMPTY_FAILURES_LIST);
    }

    public void reclaim(ReclaimPinRequest request) {
        validateReclaimPinRequest(request);
        Profile pinOwnerProfile = resolveProfile(Optional.ofNullable(request.getPinOwnerProfileId()),
                Optional.ofNullable(request.getPinOwnerEmail()));
        reclaim(request.getPin(), pinOwnerProfile);
    }

    public Pin reclaim(String pin, Profile pinOwnerProfile) {
        try {
            return pinManager.reclaim(pin, pinOwnerProfile.getProfileId());
        } catch (PinNotFoundException e) {
            throw new NotFoundException(CCPError.PinNotFound.name());
        } catch (IllegalReclaimPinRequest | ConditionalCheckFailedException e) {
            throw new UnprocessableEntityException(CCPError.PinNotReclaimed.name());
        }
    }

    private void validateReclaimPinRequest(ReclaimPinRequest request) {
        try {
            Validate.notEmpty(request.getPin(), "Pin required");
            Validate.isTrue(request.getPinOwnerProfileId() != null || request.getPinOwnerEmail() != null,
                    "Pin owner profile id or email required");
            validatePinExists(request.getPin());
        } catch (IllegalArgumentException | NullPointerException e) {
            String message = "Reclaim pin request doesn't satisfy requirements";
            log.error(message, e);
            throw new BadRequestException(message, e);
        }
    }

    public CreatePinResponse recreate(RecreatePinRequest request) {
        try {
            validateRecreatePinRequest(request);
            Profile pinOwnerProfile = resolveProfile(
                    Optional.ofNullable(request.getPinOwnerProfileId()),
                    Optional.ofNullable(request.getPinOwnerEmail()));

            Pin currentPin = pinManager.find(request.getPin());
            if (currentPin == null) {
                throw new NotFoundException(CCPError.PinNotFound.name());
            }

            pinManager.conditionalExpire(request.getPin(), pinOwnerProfile.getProfileId());

            Pin newPin = pinManager.findOrCreatePersonalPin(pinOwnerProfile);

            if (currentPin.getAlias() != null) {
                updatePinAlias(currentPin.getAlias(), newPin);
            }

            return CreatePinResponse.builder()
                .withAllocatedPins(Lists.newArrayList(PinResult.builder()
                    .withEntityId(pinOwnerProfile.getProfileId())
                    .withPin(newPin.getCode())
                    .build()))
                .build();
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new BadRequestException("Recreate pin request doesn't satisfy requirements", e);
        } catch (PinNotFoundException e) {
            throw new NotFoundException(CCPError.PinNotFound.name());
        } catch (ConditionalCheckFailedException e) {
            throw new UnprocessableEntityException(CCPError.PinNotRecreated.name());
        }
    }

    private void updatePinAlias(@NonNull String alias, @NonNull Pin newPin) {
        PinAlias pinAlias = pinAliasDao.getAlias(alias);
        if (pinAlias == null) {
            log.error("Unexpected: pin alias doesn't exist");
            return;
        }
        log.info("Pin alias exists, updating");
        pinAlias.setCode(newPin.getCode());
        pinAliasDao.update(pinAlias);

        newPin.setAlias(alias);
        pinDao.update(newPin);
    }

    private void validateRecreatePinRequest(RecreatePinRequest request) {
        Validate.notBlank(request.getPin());
        Validate.isTrue(request.getPinOwnerProfileId() != null || request.getPinOwnerEmail() != null,
                "Pin owner profile id or email required");
    }

    private void validatePinExists(String pin) {
        Pin pinModel = pinManager.find(pin);
        if (pinModel == null) {
            throw new NotFoundException(CCPError.PinNotFound.name());
        }
    }

    private Profile resolveProfile(Optional<String> profileId, Optional<String> profileEmail) {
        try {
            if (profileId.isPresent()) {
                return identityClient.getProfile(profileId.get());
            } else {
                return identityClient.getProfileByEmail(profileEmail.get());
            }
        } catch (ProfileNotFoundException e) {
            throw new NotFoundException(CCPError.ProfileNotFound.name());
        }
    }

    private Profile resolveOrCreateProfile(Optional<String> profileId, Optional<String> profileEmail) {
        try {
            if (profileId.isPresent()) {
                return identityClient.getProfile(profileId.get());
            } else {
                if (StringUtils.isBlank(profileEmail.get())) {
                    throw new BadRequestException("Email must not be blank");
                }
                return identityClient.getOrRegisterProfileByEmail(profileEmail.get(), true);
            }
        } catch (ProfileNotFoundException e) {
            throw new NotFoundException(CCPError.ProfileNotFound.name());
        }
    }
}
