
package com.amazonaws.ucbuzzccp.handler;

import com.amazonaws.services.ucbuzzprofileservice.model.Profile;
import com.amazonaws.services.ucbuzzprofileservice.model.ProfileType;
import com.amazonaws.ucbuzz.meetings.sdc.MeetingsDynamicConfigProvider;
import com.amazonaws.ucbuzzccp.AccessStatus;
import com.amazonaws.ucbuzzccp.ForbiddenException;
import com.amazonaws.ucbuzzccp.MeetingType;
import com.amazonaws.ucbuzzccp.NotFoundException;
import com.amazonaws.ucbuzzccp.common.PasscodeInfo;
import com.amazonaws.ucbuzzccp.dao.WaitingRoomAccessRequestDao;
import com.amazonaws.ucbuzzccp.dao.model.Attendee;
import com.amazonaws.ucbuzzccp.dao.model.Conference;
import com.amazonaws.ucbuzzccp.dao.model.ConferencePermissions;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

public class AnonymousSessionV3HandlerTest extends SpringUnitTestBase {

    @Mock
    protected PinHandler pinHandler;
    @Mock
    protected WaitingRoomHandler waitingRoomHandler;
    @Mock
    MeetingsDynamicConfigProvider meetingsConfig;
    @Mock
    private PasscodeParseHelper passcodeParseHelper;

    @Mock protected WaitingRoomAccessRequestDao waitingRoomAccessRequestDao;
    private static final String PIN_OWNER = "pin-owner-id";
    private static final String PIN = "valid-pin-id";
    private static final String CONF_ID = "conf-id";
    private static final String PASSCODE = "1234567890";
    private static final String WR_ID = "wr-id";
    private static final String DEVICE_ID = "device-id";
    private static final String REQUESTER_PROFILE_ID = "requester-profile-id";
    private static final String REQUESTER_WT_ACCOUNT = "requester-wt-account";
    private static final String EXISTING_ACCESS_REQUEST_ID = "existing-access-request-id";
    private V3MeetingExperienceHelper v3MeetingExperienceHelper;

    Pin pin;

    AnonymousSessionV3Handler anonymousSessionV3Handler;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        anonymousSessionV3Handler = new AnonymousSessionV3Handler();
        v3MeetingExperienceHelper = new V3MeetingExperienceHelper();
        ReflectionTestUtils.setField(anonymousSessionV3Handler, "waitingRoomHandler", waitingRoomHandler);
        ReflectionTestUtils.setField(anonymousSessionV3Handler, "waitingRoomAccessRequestDao", waitingRoomAccessRequestDao);
        ReflectionTestUtils.setField(anonymousSessionV3Handler, "v3MeetingExperienceHelper", v3MeetingExperienceHelper);
        ReflectionTestUtils.setField(v3MeetingExperienceHelper, "meetingsConfig", meetingsConfig);
        ReflectionTestUtils.setField(v3MeetingExperienceHelper, "pinHandler", pinHandler);
        ReflectionTestUtils.setField(v3MeetingExperienceHelper, "passcodeParseHelper", passcodeParseHelper);

        pin = Pin.builder().code(PIN).belongsToEntityId(PIN_OWNER).build();

        when(v3MeetingExperienceHelper.isPinOwnerAllowlistedForV3Expereince(PIN_OWNER)).thenReturn(true);
        when(passcodeParseHelper.parseAndVerifyPasscode(PASSCODE, false))
                .thenReturn(new PasscodeInfo(PASSCODE, null, null));
        //when(v3MeetingExperienceHelper.findConferencePin("PASSCODE")).thenReturn(pin);
    }

    @Test
    public void testInsertionIntoWaitingRoomAccessRequest() {
        WaitingRoom waitingRoom = WaitingRoom.builder().id(WR_ID).pin(PIN).enabled(true).build();
        Conference conference = Conference.builder().id(CONF_ID).isLocked(false).build();
        pin.setConferenceId(CONF_ID);
        when(pinHandler.findPin("PASSCODE", false)).thenReturn(pin);
        when(waitingRoomHandler
                .getOrCreateWaitingRoom(any(), any())).thenReturn(waitingRoom);
        anonymousSessionV3Handler.insertAccessRequestIntoWaitingRoom("PASSCODE", conference, "PROFILE", "DEVICE_ID","DEVICE_PLATFORM", "NAME", "36246224468");
        verify(waitingRoomAccessRequestDao, times(1)).insert(any());
    }


    @Test(expected = NotFoundException.class)
    public void testInvalidPin() {
        Conference conference = Conference.builder().id(CONF_ID).isLocked(false).build();
        pin.setConferenceId(CONF_ID);
        when(pinHandler.findPin("PASSCODE", false)).thenReturn(null);
        //when(pinHandler.findConferencePin("PASSCODE", false)).thenReturn(null);
        //when(v3MeetingExperienceHelper.findConferencePin("PASSCODE")).thenReturn(null);
        anonymousSessionV3Handler.insertAccessRequestIntoWaitingRoom("PASSCODE", conference, "PROFILE", "DEVICE_ID", "DEVICE_PLATFORM","NAME", "36246224468");
        verify(waitingRoomAccessRequestDao, times(0)).insert(any());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidConferenceId() {
        Conference conference = Conference.builder().id(CONF_ID).isLocked(false).build();
        pin.setConferenceId("DIFFERENT_ID");
        when(pinHandler.findPin("PASSCODE", false)).thenReturn(pin);
        when(waitingRoomHandler
                .getOrCreateWaitingRoom(any(), any())).thenThrow(new IllegalArgumentException("Preconditions fail"));
        anonymousSessionV3Handler.insertAccessRequestIntoWaitingRoom("PASSCODE", conference, "PROFILE", "DEVICE_ID", "DEVICE_PLATFORM", "NAME", "36246224468");
    }
}
