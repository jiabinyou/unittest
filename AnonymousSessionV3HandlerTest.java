
package com.amazonaws.ucbuzzccp.handler;

import com.amazon.coral.metrics.Metrics;
import com.amazon.metrics.declarative.MetricsManager;
import com.amazonaws.services.ucbuzzprofileservice.model.Profile;
import com.amazonaws.services.ucbuzzprofileservice.model.ProfileType;
import com.amazonaws.ucbuzz.meetings.sdc.MeetingsDynamicConfigProvider;
import com.amazonaws.ucbuzzccp.AccessStatus;
import com.amazonaws.ucbuzzccp.ForbiddenException;
import com.amazonaws.ucbuzzccp.MeetingType;
import com.amazonaws.ucbuzzccp.NotFoundException;
import com.amazonaws.ucbuzzccp.common.PasscodeInfo;
import com.amazonaws.ucbuzzccp.common.pin.PinManager;
import com.amazonaws.ucbuzzccp.dao.WaitingRoomAccessRequestDao;
import com.amazonaws.ucbuzzccp.dao.model.Attendee;
import com.amazonaws.ucbuzzccp.dao.model.Conference;
import com.amazonaws.ucbuzzccp.dao.model.ConferencePermissions;
import com.amazonaws.ucbuzzccp.dao.model.ModeratedStatus;
import com.amazonaws.ucbuzzccp.dao.model.ParkStatus;
import com.amazonaws.ucbuzzccp.dao.model.Pin;
import com.amazonaws.ucbuzzccp.dao.model.PinType;
import com.amazonaws.ucbuzzccp.dao.model.WaitingRoom;
import com.amazonaws.ucbuzzccp.dao.model.WaitingRoomAccessRequest;

import com.amazonaws.ucbuzzccp.helper.PasscodeParseHelper;
import com.amazonaws.ucbuzzccp.helper.V3MeetingExperienceHelper;
import com.amazonaws.ucbuzzccp.spring.SpringUnitTestBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static com.amazonaws.ucbuzzccp.handler.ExceptionHandler.newNotFoundExceptionWithCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.mockito.stubbing.OngoingStubbing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import static com.amazonaws.ucbuzzccp.handler.AttendeeAuthorizeHandler.ACCESS_REQUEST_EXPIRY_IN_MINUTES;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;

public class AnonymousSessionV3HandlerTest extends SpringUnitTestBase {
    private static final String HASHED_MODERATOR_CODE = "hashed-moderator-code";
    private static final String ADMIN_PROFILE_ID = UUID.randomUUID().toString();
    private static final String PIN_OWNER = "pin-owner-id";
    private static final String PINCODE = "valid-pin-id";
    private static final String CONF_ID = "conf-id";
    private static final String PASSCODE = "code";
    private static final String WR_ID = "wr-id";
    private static final String WAITING_ROOM_ID = "waiting-room-id";
    private static final String CONFERENCE_ID = UUID.randomUUID().toString();
    private static final String RODA_URL = "roda-url";

    private static final String ORGANNIZER_PROFILE_ID = "organizerProfileId";
    private static final Pin PIN = Pin.builder()
            .code(PASSCODE)
            .moderatedStatus(ModeratedStatus.MODERATED)
            .hashedModeratorCode(HASHED_MODERATOR_CODE)
            .belongsToEntityId(ADMIN_PROFILE_ID)
            .build();

    private static final WaitingRoom WAITING_ROOM = WaitingRoom.builder()
            .id(WAITING_ROOM_ID)
            .revision(1L)
            .build();

    private static final Conference CONFERENCE = Conference.builder()
            .id(CONFERENCE_ID)
            .rodaUrl(RODA_URL)
            .passcode(PASSCODE)
            .parkStatus(ParkStatus.PARKED)
            .build();

    private static final PasscodeInfo info = new PasscodeInfo(CONFERENCE_ID, null, null);

    //    @Mock
//    private MetricsManager metricsManager;

    @Mock
    private Metrics metric;
    @Mock
    protected PinHandler pinHandler;

    @Mock
    protected WaitingRoomHandler waitingRoomHandler;

    @Mock
    MeetingsDynamicConfigProvider meetingsConfig;

//    @Mock
//    private PinManager pinManager;
    @Mock
    private PasscodeParseHelper passcodeParseHelperMock;

    @Mock
    private PasscodeInfo passcodeInfo;

    @Mock protected WaitingRoomAccessRequestDao waitingRoomAccessRequestDao;

    @Mock private V3MeetingExperienceHelper v3MeetingExperienceHelper;

    @Mock Pin pinModel;
    private static final String DEVICE_ID = "device-id";
    private static final String REQUESTER_PROFILE_ID = "requester-profile-id";
    private static final String REQUESTER_WT_ACCOUNT = "requester-wt-account";
    private static final String EXISTING_ACCESS_REQUEST_ID = "existing-access-request-id";
    //private V3MeetingExperienceHelper v3MeetingExperienceHelper;

    Pin pin;
    PasscodeParseHelper passcodeParseHelper;
    //PasscodeInfo info;

    AnonymousSessionV3Handler anonymousSessionV3Handler;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        anonymousSessionV3Handler = new AnonymousSessionV3Handler();
//        v3MeetingExperienceHelper = new V3MeetingExperienceHelper();
        ReflectionTestUtils.setField(anonymousSessionV3Handler, "waitingRoomHandler", waitingRoomHandler);
        ReflectionTestUtils.setField(anonymousSessionV3Handler, "waitingRoomAccessRequestDao", waitingRoomAccessRequestDao);
        ReflectionTestUtils.setField(anonymousSessionV3Handler, "v3MeetingExperienceHelper", v3MeetingExperienceHelper);
//        ReflectionTestUtils.setField(anonymousSessionV3Handler, "pinManager", pinManager);
//        ReflectionTestUtils.setField(anonymousSessionV3Handler, "metricsManager", metricsManager);
//        ReflectionTestUtils.setField(anonymousSessionV3Handler, "passcodeInfo", passcodeInfo);
        ReflectionTestUtils.setField(anonymousSessionV3Handler, "waitingRoomAccessRequestDao", waitingRoomAccessRequestDao);
//        ReflectionTestUtils.setField(anonymousSessionV3Handler, "pinHandler", pinHandler);
        ReflectionTestUtils.setField(anonymousSessionV3Handler, "pinModel", pinModel);
//        ReflectionTestUtils.setField(anonymousSessionV3Handler, "", );
        ReflectionTestUtils.setField(v3MeetingExperienceHelper, "meetingsConfig", meetingsConfig);
        ReflectionTestUtils.setField(v3MeetingExperienceHelper, "pinHandler", pinHandler);
        ReflectionTestUtils.setField(v3MeetingExperienceHelper, "passcodeParseHelper", passcodeParseHelperMock);


        pin = Pin.builder().code(PINCODE).belongsToEntityId(PIN_OWNER).build();
        passcodeParseHelper = new PasscodeParseHelper();
        //info = new PasscodeInfo();
        //when(pinManager.find(PASSCODE)).thenReturn(PIN);
        //when(metricsManager.get()).thenReturn(metric);
        when(waitingRoomHandler.getWaitingRoom(WAITING_ROOM_ID)).thenReturn(WAITING_ROOM);
        when(waitingRoomHandler.getOrCreateWaitingRoom(PIN, CONFERENCE)).thenReturn(WAITING_ROOM);
//        when(v3MeetingExperienceHelper.findConferencePin(PASSCODE)).thenReturn(PIN);
        when(v3MeetingExperienceHelper.isPinOwnerAllowlistedForV3Expereince(PIN_OWNER)).thenReturn(true);
//        when(passcodeParseHelper.parseAndVerifyPasscode(PASSCODE, false))
//                .thenReturn(new PasscodeInfo(PASSCODE, null, null));
    }

    @Test
    public void testInsertionIntoWaitingRoomAccessRequest() {
//        WaitingRoom waitingRoom = WaitingRoom.builder().id(WR_ID).pin(PINCODE).enabled(true).build();
//        Conference conference = Conference.builder().id(CONF_ID).isLocked(false).build();
//        pin.setConferenceId(CONF_ID);
//        info = new PasscodeInfo();
//        info.setConferencePin("VAPASSCODE");

        //when(info.getConferencePin()).thenReturn("VAPASSCODE");
        //when(pinHandler.findPin("VAPASSCODE", false)).thenReturn(PIN);
        //when(passcodeParseHelperMock.parseAndVerifyPasscode("VAPASSCODE", false)).thenReturn(info);
        //when(waitingRoomHandler
        //        .getOrCreateWaitingRoom(any(), any())).thenReturn(waitingRoom);
        //when(v3MeetingExperienceHelper.findConferencePin(PASSCODE)).thenReturn(pin);
//        OngoingStubbing<PasscodeInfo> vapasscode = when(passcodeParseHelperMock.parseAndVerifyPasscode("VAPASSCODE", false));
//        System.out.printf("", vapasscode);
        //)
                //.thenReturn(info);


        when(pinHandler.findPin("VAPASSCODE", false)).thenReturn(PIN);
        when(passcodeParseHelperMock.parseAndVerifyPasscode("VAPASSCODE", false)).thenReturn(info);
        when(v3MeetingExperienceHelper.findConferencePin(PASSCODE)).thenReturn(PIN);
        when(pinModel.getProfileId()).thenReturn(ORGANNIZER_PROFILE_ID);
        anonymousSessionV3Handler.insertAccessRequestIntoWaitingRoom("1234567890", CONFERENCE, "PROFILE", "DEVICE_ID","DEVICE_PLATFORM", "NAME", "36246224468");
        verify(waitingRoomAccessRequestDao, times(1)).insert(any());
    }


    @Test(expected = NotFoundException.class)
    public void testInvalidPin() {
        //Conference conference = Conference.builder().id(CONF_ID).isLocked(false).build();
        //pin.setConferenceId(CONF_ID);
        //when(passcodeInfo.getConferencePin()).thenReturn("pin");
        //when(pinHandler.findPin("PASSCODE", false)).thenReturn(pin);
        when(pinHandler.findPin("VAPASSCODE", false)).thenReturn(PIN);
        when(passcodeParseHelperMock.parseAndVerifyPasscode("VAPASSCODE", false)).thenReturn(info);
//        when(passcodeParseHelperMock.parseAndVerifyPasscode("VALIDPASSCODE", false))
//                .thenReturn(new PasscodeInfo("VALIDPASSCODE", null, null));
        //when(v3MeetingExperienceHelper.findConferencePin(PASSCODE)).thenReturn(PIN);
        when(v3MeetingExperienceHelper.findConferencePin(PASSCODE)).thenThrow(new NotFoundException("Cannot Find Conference Pin"));
        when(pinModel.getProfileId()).thenReturn(ORGANNIZER_PROFILE_ID);
        anonymousSessionV3Handler.insertAccessRequestIntoWaitingRoom("PASSCODE", CONFERENCE, "PROFILE", "DEVICE_ID", "DEVICE_PLATFORM","NAME", "36246224468");
        //verify(waitingRoomAccessRequestDao, times(0)).insert(any());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidConferenceId() {
//        Conference conference = Conference.builder().id(CONF_ID).isLocked(false).build();
//        pin.setConferenceId("DIFFERENT_ID");
//        when(passcodeInfo.getConferencePin()).thenReturn("pin");
//        when(pinHandler.findPin("PASSCODE", false)).thenReturn(pin);
//        when(passcodeParseHelper.parseAndVerifyPasscode("VAPASSCODE", false))
//                .thenReturn(new PasscodeInfo("VAPASSCODE", null, null));

        when(pinHandler.findPin("VAPASSCODE", false)).thenReturn(PIN);
        when(passcodeParseHelperMock.parseAndVerifyPasscode("VAPASSCODE", false)).thenReturn(info);
        //        when(passcodeParseHelperMock.parseAndVerifyPasscode("VALIDPASSCODE", false))
        //                .thenReturn(new PasscodeInfo("VALIDPASSCODE", null, null));
        when(v3MeetingExperienceHelper.findConferencePin(PASSCODE)).thenReturn(PIN);
        when(pinModel.getProfileId()).thenReturn(ORGANNIZER_PROFILE_ID);
        when(waitingRoomHandler
                .getOrCreateWaitingRoom(any(), any())).thenThrow(new IllegalArgumentException("Preconditions fail"));
        anonymousSessionV3Handler.insertAccessRequestIntoWaitingRoom("PASSCODE", CONFERENCE, "PROFILE", "DEVICE_ID", "DEVICE_PLATFORM", "NAME", "36246224468");
    }
}
