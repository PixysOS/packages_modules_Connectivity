/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import static android.Manifest.permission.BIND_VPN_SERVICE;
import static android.Manifest.permission.CONTROL_VPN;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.ConnectivityManager.NetworkCallback;
import static android.net.INetd.IF_STATE_DOWN;
import static android.net.INetd.IF_STATE_UP;
import static android.net.RouteInfo.RTN_UNREACHABLE;
import static android.net.VpnManager.TYPE_VPN_PLATFORM;
import static android.net.ipsec.ike.IkeSessionConfiguration.EXTENSION_TYPE_MOBIKE;
import static android.os.Build.VERSION_CODES.S_V2;
import static android.os.UserHandle.PER_USER_RANGE;

import static com.android.modules.utils.build.SdkLevel.isAtLeastT;
import static com.android.testutils.Cleanup.testAndCleanup;
import static com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.Ikev2VpnProfile;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LocalSocket;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo.DetailedState;
import android.net.RouteInfo;
import android.net.UidRangeParcel;
import android.net.VpnManager;
import android.net.VpnProfileState;
import android.net.VpnService;
import android.net.VpnTransportInfo;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionConfiguration;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionConfiguration;
import android.net.ipsec.ike.IkeSessionConnectionInfo;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.net.ipsec.ike.exceptions.IkeException;
import android.net.ipsec.ike.exceptions.IkeNetworkLostException;
import android.net.ipsec.ike.exceptions.IkeNonProtocolException;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.net.ipsec.ike.exceptions.IkeTimeoutException;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.INetworkManagementService;
import android.os.ParcelFileDescriptor;
import android.os.PowerWhitelistManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.security.Credentials;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Range;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.util.HexDump;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.DeviceIdleInternal;
import com.android.server.IpSecService;
import com.android.server.VpnTestBase;
import com.android.server.vcn.util.PersistableBundleUtils;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Tests for {@link Vpn}.
 *
 * Build, install and run with:
 *  runtest frameworks-net -c com.android.server.connectivity.VpnTest
 */
@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@IgnoreUpTo(S_V2)
public class VpnTest extends VpnTestBase {
    private static final String TAG = "VpnTest";

    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    static final Network EGRESS_NETWORK = new Network(101);
    static final String EGRESS_IFACE = "wlan0";
    private static final String TEST_VPN_CLIENT = "2.4.6.8";
    private static final String TEST_VPN_SERVER = "1.2.3.4";
    private static final String TEST_VPN_IDENTITY = "identity";
    private static final byte[] TEST_VPN_PSK = "psk".getBytes();

    private static final int IP4_PREFIX_LEN = 32;
    private static final int MIN_PORT = 0;
    private static final int MAX_PORT = 65535;

    private static final InetAddress TEST_VPN_CLIENT_IP =
            InetAddresses.parseNumericAddress(TEST_VPN_CLIENT);
    private static final InetAddress TEST_VPN_SERVER_IP =
            InetAddresses.parseNumericAddress(TEST_VPN_SERVER);
    private static final InetAddress TEST_VPN_CLIENT_IP_2 =
            InetAddresses.parseNumericAddress("192.0.2.200");
    private static final InetAddress TEST_VPN_SERVER_IP_2 =
            InetAddresses.parseNumericAddress("192.0.2.201");
    private static final InetAddress TEST_VPN_INTERNAL_IP =
            InetAddresses.parseNumericAddress("198.51.100.10");
    private static final InetAddress TEST_VPN_INTERNAL_DNS =
            InetAddresses.parseNumericAddress("8.8.8.8");

    private static final IkeTrafficSelector IN_TS =
            new IkeTrafficSelector(MIN_PORT, MAX_PORT, TEST_VPN_INTERNAL_IP, TEST_VPN_INTERNAL_IP);
    private static final IkeTrafficSelector OUT_TS =
            new IkeTrafficSelector(MIN_PORT, MAX_PORT,
                    InetAddresses.parseNumericAddress("0.0.0.0"),
                    InetAddresses.parseNumericAddress("255.255.255.255"));

    private static final Network TEST_NETWORK = new Network(Integer.MAX_VALUE);
    private static final Network TEST_NETWORK_2 = new Network(Integer.MAX_VALUE - 1);
    private static final String TEST_IFACE_NAME = "TEST_IFACE";
    private static final int TEST_TUNNEL_RESOURCE_ID = 0x2345;
    private static final long TEST_TIMEOUT_MS = 500L;
    private static final String PRIMARY_USER_APP_EXCLUDE_KEY =
            "VPNAPPEXCLUDED_27_com.testvpn.vpn";
    static final String PKGS_BYTES = getPackageByteString(List.of(PKGS));
    private static final Range<Integer> PRIMARY_USER_RANGE = uidRangeForUser(PRIMARY_USER.id);

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private INetworkManagementService mNetService;
    @Mock private INetd mNetd;
    @Mock private AppOpsManager mAppOps;
    @Mock private NotificationManager mNotificationManager;
    @Mock private Vpn.SystemServices mSystemServices;
    @Mock private Vpn.IkeSessionWrapper mIkeSessionWrapper;
    @Mock private Vpn.Ikev2SessionCreator mIkev2SessionCreator;
    @Mock private Vpn.VpnNetworkAgentWrapper mMockNetworkAgent;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private IpSecService mIpSecService;
    @Mock private VpnProfileStore mVpnProfileStore;
    @Mock private ScheduledThreadPoolExecutor mExecutor;
    @Mock private ScheduledFuture mScheduledFuture;
    @Mock DeviceIdleInternal mDeviceIdleInternal;
    private final VpnProfile mVpnProfile;

    private IpSecManager mIpSecManager;

    private TestDeps mTestDeps;

    public VpnTest() throws Exception {
        // Build an actual VPN profile that is capable of being converted to and from an
        // Ikev2VpnProfile
        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(TEST_VPN_SERVER, TEST_VPN_IDENTITY);
        builder.setAuthPsk(TEST_VPN_PSK);
        mVpnProfile = builder.build().toVpnProfile();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mIpSecManager = new IpSecManager(mContext, mIpSecService);
        mTestDeps = spy(new TestDeps());

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        setMockedPackages(sPackages);

        when(mContext.getPackageName()).thenReturn(TEST_VPN_PKG);
        when(mContext.getOpPackageName()).thenReturn(TEST_VPN_PKG);
        mockService(UserManager.class, Context.USER_SERVICE, mUserManager);
        mockService(AppOpsManager.class, Context.APP_OPS_SERVICE, mAppOps);
        mockService(NotificationManager.class, Context.NOTIFICATION_SERVICE, mNotificationManager);
        mockService(ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mConnectivityManager);
        mockService(IpSecManager.class, Context.IPSEC_SERVICE, mIpSecManager);
        when(mContext.getString(R.string.config_customVpnAlwaysOnDisconnectedDialogComponent))
                .thenReturn(Resources.getSystem().getString(
                        R.string.config_customVpnAlwaysOnDisconnectedDialogComponent));
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS))
                .thenReturn(true);

        // Used by {@link Notification.Builder}
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = VERSION_CODES.CUR_DEVELOPMENT;
        when(mContext.getApplicationInfo()).thenReturn(applicationInfo);
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);

        doNothing().when(mNetService).registerObserver(any());

        // Deny all appops by default.
        when(mAppOps.noteOpNoThrow(anyString(), anyInt(), anyString(), any(), any()))
                .thenReturn(AppOpsManager.MODE_IGNORED);

        // Setup IpSecService
        final IpSecTunnelInterfaceResponse tunnelResp =
                new IpSecTunnelInterfaceResponse(
                        IpSecManager.Status.OK, TEST_TUNNEL_RESOURCE_ID, TEST_IFACE_NAME);
        when(mIpSecService.createTunnelInterface(any(), any(), any(), any(), any()))
                .thenReturn(tunnelResp);
        // The unit test should know what kind of permission it needs and set the permission by
        // itself, so set the default value of Context#checkCallingOrSelfPermission to
        // PERMISSION_DENIED.
        doReturn(PERMISSION_DENIED).when(mContext).checkCallingOrSelfPermission(any());

        // Set up mIkev2SessionCreator and mExecutor
        resetIkev2SessionCreator(mIkeSessionWrapper);
        resetExecutor(mScheduledFuture);
    }

    private void resetIkev2SessionCreator(Vpn.IkeSessionWrapper ikeSession) {
        reset(mIkev2SessionCreator);
        when(mIkev2SessionCreator.createIkeSession(any(), any(), any(), any(), any(), any()))
                .thenReturn(ikeSession);
    }

    private void resetExecutor(ScheduledFuture scheduledFuture) {
        doAnswer(
                (invocation) -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
            .when(mExecutor)
            .execute(any());
        when(mExecutor.schedule(
                any(Runnable.class), anyLong(), any())).thenReturn(mScheduledFuture);
    }

    @After
    public void tearDown() throws Exception {
        doReturn(PERMISSION_DENIED).when(mContext).checkCallingOrSelfPermission(CONTROL_VPN);
    }

    private <T> void mockService(Class<T> clazz, String name, T service) {
        doReturn(service).when(mContext).getSystemService(name);
        doReturn(name).when(mContext).getSystemServiceName(clazz);
        if (mContext.getSystemService(clazz).getClass().equals(Object.class)) {
            // Test is using mockito-extended (mContext uses Answers.RETURNS_DEEP_STUBS and returned
            // a mock object on a final method)
            doCallRealMethod().when(mContext).getSystemService(clazz);
        }
    }

    private Set<Range<Integer>> rangeSet(Range<Integer> ... ranges) {
        final Set<Range<Integer>> range = new ArraySet<>();
        for (Range<Integer> r : ranges) range.add(r);

        return range;
    }

    private static Range<Integer> uidRangeForUser(int userId) {
        return new Range<Integer>(userId * PER_USER_RANGE, (userId + 1) * PER_USER_RANGE - 1);
    }

    private Range<Integer> uidRange(int start, int stop) {
        return new Range<Integer>(start, stop);
    }

    private static String getPackageByteString(List<String> packages) {
        try {
            return HexDump.toHexString(
                    PersistableBundleUtils.toDiskStableBytes(PersistableBundleUtils.fromList(
                            packages, PersistableBundleUtils.STRING_SERIALIZER)),
                        true /* upperCase */);
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    public void testRestrictedProfilesAreAddedToVpn() {
        setMockedUsers(PRIMARY_USER, SECONDARY_USER, RESTRICTED_PROFILE_A, RESTRICTED_PROFILE_B);

        final Vpn vpn = createVpn(PRIMARY_USER.id);

        // Assume the user can have restricted profiles.
        doReturn(true).when(mUserManager).canHaveRestrictedProfile();
        final Set<Range<Integer>> ranges =
                vpn.createUserAndRestrictedProfilesRanges(PRIMARY_USER.id, null, null);

        assertEquals(rangeSet(PRIMARY_USER_RANGE, uidRangeForUser(RESTRICTED_PROFILE_A.id)),
                 ranges);
    }

    @Test
    public void testManagedProfilesAreNotAddedToVpn() {
        setMockedUsers(PRIMARY_USER, MANAGED_PROFILE_A);

        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Set<Range<Integer>> ranges = vpn.createUserAndRestrictedProfilesRanges(
                PRIMARY_USER.id, null, null);

        assertEquals(rangeSet(PRIMARY_USER_RANGE), ranges);
    }

    @Test
    public void testAddUserToVpnOnlyAddsOneUser() {
        setMockedUsers(PRIMARY_USER, RESTRICTED_PROFILE_A, MANAGED_PROFILE_A);

        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Set<Range<Integer>> ranges = new ArraySet<>();
        vpn.addUserToRanges(ranges, PRIMARY_USER.id, null, null);

        assertEquals(rangeSet(PRIMARY_USER_RANGE), ranges);
    }

    @Test
    public void testUidAllowAndDenylist() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Range<Integer> user = PRIMARY_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        final String[] packages = {PKGS[0], PKGS[1], PKGS[2]};

        // Allowed list
        final Set<Range<Integer>> allow = vpn.createUserAndRestrictedProfilesRanges(PRIMARY_USER.id,
                Arrays.asList(packages), null /* disallowedApplications */);
        assertEquals(rangeSet(
                uidRange(userStart + PKG_UIDS[0], userStart + PKG_UIDS[0]),
                uidRange(userStart + PKG_UIDS[1], userStart + PKG_UIDS[2]),
                uidRange(Process.toSdkSandboxUid(userStart + PKG_UIDS[0]),
                         Process.toSdkSandboxUid(userStart + PKG_UIDS[0])),
                uidRange(Process.toSdkSandboxUid(userStart + PKG_UIDS[1]),
                         Process.toSdkSandboxUid(userStart + PKG_UIDS[2]))),
                allow);

        // Denied list
        final Set<Range<Integer>> disallow =
                vpn.createUserAndRestrictedProfilesRanges(PRIMARY_USER.id,
                        null /* allowedApplications */, Arrays.asList(packages));
        assertEquals(rangeSet(
                uidRange(userStart, userStart + PKG_UIDS[0] - 1),
                uidRange(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[1] - 1),
                /* Empty range between UIDS[1] and UIDS[2], should be excluded, */
                uidRange(userStart + PKG_UIDS[2] + 1,
                         Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                uidRange(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                         Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                uidRange(Process.toSdkSandboxUid(userStart + PKG_UIDS[2] + 1), userStop)),
                disallow);
    }

    private void verifyPowerSaveTempWhitelistApp(String packageName) {
        verify(mDeviceIdleInternal).addPowerSaveTempWhitelistApp(anyInt(), eq(packageName),
                anyLong(), anyInt(), eq(false), eq(PowerWhitelistManager.REASON_VPN),
                eq("VpnManager event"));
    }

    @Test
    public void testGetAlwaysAndOnGetLockDown() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);

        // Default state.
        assertFalse(vpn.getAlwaysOn());
        assertFalse(vpn.getLockdown());

        // Set always-on without lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false, Collections.emptyList()));
        assertTrue(vpn.getAlwaysOn());
        assertFalse(vpn.getLockdown());

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true, Collections.emptyList()));
        assertTrue(vpn.getAlwaysOn());
        assertTrue(vpn.getLockdown());

        // Remove always-on configuration.
        assertTrue(vpn.setAlwaysOnPackage(null, false, Collections.emptyList()));
        assertFalse(vpn.getAlwaysOn());
        assertFalse(vpn.getLockdown());
    }

    @Test
    public void testLockdownChangingPackage() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Range<Integer> user = PRIMARY_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        // Set always-on without lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false, null));

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1), userStop)
        }));

        // Switch to another app.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[3], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1), userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[3] + 1), userStop)
        }));
    }

    @Test
    public void testLockdownAllowlist() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Range<Integer> user = PRIMARY_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        // Set always-on with lockdown and allow app PKGS[2] from lockdown.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], true, Collections.singletonList(PKGS[2])));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[]  {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1]) - 1),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[2] + 1), userStop)
        }));
        // Change allowed app list to PKGS[3].
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], true, Collections.singletonList(PKGS[3])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[2] + 1), userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[3] + 1), userStop)
        }));

        // Change the VPN app.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true, Collections.singletonList(PKGS[3])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1))
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[0] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1))
        }));

        // Remove the list of allowed packages.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[0], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[3] + 1), userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1), userStop),
        }));

        // Add the list of allowed packages.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true, Collections.singletonList(PKGS[1])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1), userStop),
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1), userStop)
        }));

        // Try allowing a package with a comma, should be rejected.
        assertFalse(vpn.setAlwaysOnPackage(
                PKGS[0], true, Collections.singletonList("a.b,c.d")));

        // Pass a non-existent packages in the allowlist, they (and only they) should be ignored.
        // allowed package should change from PGKS[1] to PKGS[2].
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true, Arrays.asList("com.foo.app", PKGS[2], "com.bar.app")));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1), userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[2] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[2] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[2] + 1), userStop)
        }));
    }

    @Test
    public void testLockdownRuleRepeatability() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final UidRangeParcel[] primaryUserRangeParcel = new UidRangeParcel[] {
                new UidRangeParcel(PRIMARY_USER_RANGE.getLower(), PRIMARY_USER_RANGE.getUpper())};
        // Given legacy lockdown is already enabled,
        vpn.setLockdown(true);
        verify(mConnectivityManager, times(1)).setRequireVpnForUids(true,
                toRanges(primaryUserRangeParcel));

        // Enabling legacy lockdown twice should do nothing.
        vpn.setLockdown(true);
        verify(mConnectivityManager, times(1)).setRequireVpnForUids(anyBoolean(), any());

        // And disabling should remove the rules exactly once.
        vpn.setLockdown(false);
        verify(mConnectivityManager, times(1)).setRequireVpnForUids(false,
                toRanges(primaryUserRangeParcel));

        // Removing the lockdown again should have no effect.
        vpn.setLockdown(false);
        verify(mConnectivityManager, times(2)).setRequireVpnForUids(anyBoolean(), any());
    }

    private ArrayList<Range<Integer>> toRanges(UidRangeParcel[] ranges) {
        ArrayList<Range<Integer>> rangesArray = new ArrayList<>(ranges.length);
        for (int i = 0; i < ranges.length; i++) {
            rangesArray.add(new Range<>(ranges[i].start, ranges[i].stop));
        }
        return rangesArray;
    }

    @Test
    public void testLockdownRuleReversibility() throws Exception {
        doReturn(PERMISSION_GRANTED).when(mContext).checkCallingOrSelfPermission(CONTROL_VPN);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final UidRangeParcel[] entireUser = {
            new UidRangeParcel(PRIMARY_USER_RANGE.getLower(), PRIMARY_USER_RANGE.getUpper())
        };
        final UidRangeParcel[] exceptPkg0 = {
            new UidRangeParcel(entireUser[0].start, entireUser[0].start + PKG_UIDS[0] - 1),
            new UidRangeParcel(entireUser[0].start + PKG_UIDS[0] + 1,
                               Process.toSdkSandboxUid(entireUser[0].start + PKG_UIDS[0] - 1)),
            new UidRangeParcel(Process.toSdkSandboxUid(entireUser[0].start + PKG_UIDS[0] + 1),
                               entireUser[0].stop),
        };

        final InOrder order = inOrder(mConnectivityManager);

        // Given lockdown is enabled with no package (legacy VPN),
        vpn.setLockdown(true);
        order.verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(entireUser));

        // When a new VPN package is set the rules should change to cover that package.
        vpn.prepare(null, PKGS[0], VpnManager.TYPE_VPN_SERVICE);
        order.verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(entireUser));
        order.verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(exceptPkg0));

        // When that VPN package is unset, everything should be undone again in reverse.
        vpn.prepare(null, VpnConfig.LEGACY_VPN, VpnManager.TYPE_VPN_SERVICE);
        order.verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(exceptPkg0));
        order.verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(entireUser));
    }

    @Test
    public void testPrepare_throwSecurityExceptionWhenGivenPackageDoesNotBelongToTheCaller()
            throws Exception {
        assumeTrue(isAtLeastT());
        final Vpn vpn = createVpnAndSetupUidChecks();
        assertThrows(SecurityException.class,
                () -> vpn.prepare("com.not.vpn.owner", null, VpnManager.TYPE_VPN_SERVICE));
        assertThrows(SecurityException.class,
                () -> vpn.prepare(null, "com.not.vpn.owner", VpnManager.TYPE_VPN_SERVICE));
        assertThrows(SecurityException.class,
                () -> vpn.prepare("com.not.vpn.owner1", "com.not.vpn.owner2",
                        VpnManager.TYPE_VPN_SERVICE));
    }

    @Test
    public void testPrepare_bothOldPackageAndNewPackageAreNull() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();
        assertTrue(vpn.prepare(null, null, VpnManager.TYPE_VPN_SERVICE));

    }

    @Test
    public void testIsAlwaysOnPackageSupported() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);

        ApplicationInfo appInfo = new ApplicationInfo();
        when(mPackageManager.getApplicationInfoAsUser(eq(PKGS[0]), anyInt(), eq(PRIMARY_USER.id)))
                .thenReturn(appInfo);

        ServiceInfo svcInfo = new ServiceInfo();
        ResolveInfo resInfo = new ResolveInfo();
        resInfo.serviceInfo = svcInfo;
        when(mPackageManager.queryIntentServicesAsUser(any(), eq(PackageManager.GET_META_DATA),
                eq(PRIMARY_USER.id)))
                .thenReturn(Collections.singletonList(resInfo));

        // null package name should return false
        assertFalse(vpn.isAlwaysOnPackageSupported(null));

        // Pre-N apps are not supported
        appInfo.targetSdkVersion = VERSION_CODES.M;
        assertFalse(vpn.isAlwaysOnPackageSupported(PKGS[0]));

        // N+ apps are supported by default
        appInfo.targetSdkVersion = VERSION_CODES.N;
        assertTrue(vpn.isAlwaysOnPackageSupported(PKGS[0]));

        // Apps that opt out explicitly are not supported
        appInfo.targetSdkVersion = VERSION_CODES.CUR_DEVELOPMENT;
        Bundle metaData = new Bundle();
        metaData.putBoolean(VpnService.SERVICE_META_DATA_SUPPORTS_ALWAYS_ON, false);
        svcInfo.metaData = metaData;
        assertFalse(vpn.isAlwaysOnPackageSupported(PKGS[0]));
    }

    @Test
    public void testNotificationShownForAlwaysOnApp() throws Exception {
        final UserHandle userHandle = UserHandle.of(PRIMARY_USER.id);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        setMockedUsers(PRIMARY_USER);

        final InOrder order = inOrder(mNotificationManager);

        // Don't show a notification for regular disconnected states.
        vpn.updateState(DetailedState.DISCONNECTED, TAG);
        order.verify(mNotificationManager, atLeastOnce()).cancel(anyString(), anyInt());

        // Start showing a notification for disconnected once always-on.
        vpn.setAlwaysOnPackage(PKGS[0], false, null);
        order.verify(mNotificationManager).notify(anyString(), anyInt(), any());

        // Stop showing the notification once connected.
        vpn.updateState(DetailedState.CONNECTED, TAG);
        order.verify(mNotificationManager).cancel(anyString(), anyInt());

        // Show the notification if we disconnect again.
        vpn.updateState(DetailedState.DISCONNECTED, TAG);
        order.verify(mNotificationManager).notify(anyString(), anyInt(), any());

        // Notification should be cleared after unsetting always-on package.
        vpn.setAlwaysOnPackage(null, false, null);
        order.verify(mNotificationManager).cancel(anyString(), anyInt());
    }

    /**
     * The profile name should NOT change between releases for backwards compatibility
     *
     * <p>If this is changed between releases, the {@link Vpn#getVpnProfilePrivileged()} method MUST
     * be updated to ensure backward compatibility.
     */
    @Test
    public void testGetProfileNameForPackage() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        setMockedUsers(PRIMARY_USER);

        final String expected = Credentials.PLATFORM_VPN + PRIMARY_USER.id + "_" + TEST_VPN_PKG;
        assertEquals(expected, vpn.getProfileNameForPackage(TEST_VPN_PKG));
    }

    private Vpn createVpnAndSetupUidChecks(String... grantedOps) throws Exception {
        return createVpnAndSetupUidChecks(PRIMARY_USER, grantedOps);
    }

    private Vpn createVpnAndSetupUidChecks(UserInfo user, String... grantedOps) throws Exception {
        final Vpn vpn = createVpn(user.id);
        setMockedUsers(user);

        when(mPackageManager.getPackageUidAsUser(eq(TEST_VPN_PKG), anyInt()))
                .thenReturn(Process.myUid());

        for (final String opStr : grantedOps) {
            when(mAppOps.noteOpNoThrow(opStr, Process.myUid(), TEST_VPN_PKG,
                    null /* attributionTag */, null /* message */))
                    .thenReturn(AppOpsManager.MODE_ALLOWED);
        }

        return vpn;
    }

    private void checkProvisionVpnProfile(Vpn vpn, boolean expectedResult, String... checkedOps) {
        assertEquals(expectedResult, vpn.provisionVpnProfile(TEST_VPN_PKG, mVpnProfile));

        // The profile should always be stored, whether or not consent has been previously granted.
        verify(mVpnProfileStore)
                .put(
                        eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)),
                        eq(mVpnProfile.encode()));

        for (final String checkedOpStr : checkedOps) {
            verify(mAppOps).noteOpNoThrow(checkedOpStr, Process.myUid(), TEST_VPN_PKG,
                    null /* attributionTag */, null /* message */);
        }
    }

    @Test
    public void testProvisionVpnProfileNoIpsecTunnels() throws Exception {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS))
                .thenReturn(false);
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            checkProvisionVpnProfile(
                    vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
            fail("Expected exception due to missing feature");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private Vpn prepareVpnForVerifyAppExclusionList() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());
        when(mVpnProfileStore.get(PRIMARY_USER_APP_EXCLUDE_KEY))
                .thenReturn(HexDump.hexStringToByteArray(PKGS_BYTES));

        vpn.startVpnProfile(TEST_VPN_PKG);
        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
        vpn.mNetworkAgent = mMockNetworkAgent;
        return vpn;
    }

    @Test
    public void testSetAndGetAppExclusionList() throws Exception {
        final Vpn vpn = prepareVpnForVerifyAppExclusionList();
        verify(mVpnProfileStore, never()).put(eq(PRIMARY_USER_APP_EXCLUDE_KEY), any());
        vpn.setAppExclusionList(TEST_VPN_PKG, Arrays.asList(PKGS));
        verify(mVpnProfileStore)
                .put(eq(PRIMARY_USER_APP_EXCLUDE_KEY),
                     eq(HexDump.hexStringToByteArray(PKGS_BYTES)));
        assertEquals(vpn.createUserAndRestrictedProfilesRanges(
                PRIMARY_USER.id, null, Arrays.asList(PKGS)),
                vpn.mNetworkCapabilities.getUids());
        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));
    }

    @Test
    public void testRefreshPlatformVpnAppExclusionList_updatesExcludedUids() throws Exception {
        final Vpn vpn = prepareVpnForVerifyAppExclusionList();
        vpn.setAppExclusionList(TEST_VPN_PKG, Arrays.asList(PKGS));
        verify(mMockNetworkAgent).doSendNetworkCapabilities(any());
        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));

        reset(mMockNetworkAgent);

        // Remove one of the package
        List<Integer> newExcludedUids = toList(PKG_UIDS);
        newExcludedUids.remove((Integer) PKG_UIDS[0]);
        sPackages.remove(PKGS[0]);
        vpn.refreshPlatformVpnAppExclusionList();

        // List in keystore is not changed, but UID for the removed packages is no longer exempted.
        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));
        assertEquals(makeVpnUidRange(PRIMARY_USER.id, newExcludedUids),
                vpn.mNetworkCapabilities.getUids());
        ArgumentCaptor<NetworkCapabilities> ncCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mMockNetworkAgent).doSendNetworkCapabilities(ncCaptor.capture());
        assertEquals(makeVpnUidRange(PRIMARY_USER.id, newExcludedUids),
                ncCaptor.getValue().getUids());

        reset(mMockNetworkAgent);

        // Add the package back
        newExcludedUids.add(PKG_UIDS[0]);
        sPackages.put(PKGS[0], PKG_UIDS[0]);
        vpn.refreshPlatformVpnAppExclusionList();

        // List in keystore is not changed and the uid list should be updated in the net cap.
        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));
        assertEquals(makeVpnUidRange(PRIMARY_USER.id, newExcludedUids),
                vpn.mNetworkCapabilities.getUids());
        verify(mMockNetworkAgent).doSendNetworkCapabilities(ncCaptor.capture());
        assertEquals(makeVpnUidRange(PRIMARY_USER.id, newExcludedUids),
                ncCaptor.getValue().getUids());
    }

    private Set<Range<Integer>> makeVpnUidRange(int userId, List<Integer> excludedList) {
        final SortedSet<Integer> list = new TreeSet<>();

        final int userBase = userId * UserHandle.PER_USER_RANGE;
        for (int uid : excludedList) {
            final int applicationUid = UserHandle.getUid(userId, uid);
            list.add(applicationUid);
            list.add(Process.toSdkSandboxUid(applicationUid)); // Add Sdk Sandbox UID
        }

        final int minUid = userBase;
        final int maxUid = userBase + UserHandle.PER_USER_RANGE - 1;
        final Set<Range<Integer>> ranges = new ArraySet<>();

        // Iterate the list to create the ranges between each uid.
        int start = minUid;
        for (int uid : list) {
            if (uid == start) {
                start++;
            } else {
                ranges.add(new Range<>(start, uid - 1));
                start = uid + 1;
            }
        }

        // Create the range between last uid and max uid.
        if (start <= maxUid) {
            ranges.add(new Range<>(start, maxUid));
        }

        return ranges;
    }

    @Test
    public void testSetAndGetAppExclusionListRestrictedUser() throws Exception {
        final Vpn vpn = prepareVpnForVerifyAppExclusionList();
        // Mock it to restricted profile
        when(mUserManager.getUserInfo(anyInt())).thenReturn(RESTRICTED_PROFILE_A);
        // Restricted users cannot configure VPNs
        assertThrows(SecurityException.class,
                () -> vpn.setAppExclusionList(TEST_VPN_PKG, new ArrayList<>()));
        assertThrows(SecurityException.class, () -> vpn.getAppExclusionList(TEST_VPN_PKG));
    }

    @Test
    public void testProvisionVpnProfilePreconsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        checkProvisionVpnProfile(
                vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
    }

    @Test
    public void testProvisionVpnProfileNotPreconsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        // Expect that both the ACTIVATE_VPN and ACTIVATE_PLATFORM_VPN were tried, but the caller
        // had neither.
        checkProvisionVpnProfile(vpn, false /* expectedResult */,
                AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN, AppOpsManager.OPSTR_ACTIVATE_VPN);
    }

    private void setAppOpsPermission() {
        doAnswer(invocation -> {
            when(mAppOps.noteOpNoThrow(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN,
                    Process.myUid(), TEST_VPN_PKG,
                    null /* attributionTag */, null /* message */))
                    .thenReturn(AppOpsManager.MODE_ALLOWED);
            return null;
        }).when(mAppOps).setMode(
                eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                eq(Process.myUid()),
                eq(TEST_VPN_PKG),
                eq(AppOpsManager.MODE_ALLOWED));
    }

    @Test
    public void testProvisionVpnProfileNotPreconsented_withControlVpnPermission() throws Exception {
        setAppOpsPermission();
        doReturn(PERMISSION_GRANTED).when(mContext).checkCallingOrSelfPermission(CONTROL_VPN);
        final Vpn vpn = createVpnAndSetupUidChecks();

        // ACTIVATE_PLATFORM_VPN will be granted if VPN app has CONTROL_VPN permission.
        checkProvisionVpnProfile(vpn, true /* expectedResult */,
                AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
    }

    @Test
    public void testProvisionVpnProfileVpnServicePreconsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_VPN);

        checkProvisionVpnProfile(vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_VPN);
    }

    @Test
    public void testProvisionVpnProfileTooLarge() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        final VpnProfile bigProfile = new VpnProfile("");
        bigProfile.name = new String(new byte[Vpn.MAX_VPN_PROFILE_SIZE_BYTES + 1]);

        try {
            vpn.provisionVpnProfile(TEST_VPN_PKG, bigProfile);
            fail("Expected IAE due to profile size");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testProvisionVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpnAndSetupUidChecks(
                        RESTRICTED_PROFILE_A, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.provisionVpnProfile(TEST_VPN_PKG, mVpnProfile);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testDeleteVpnProfile() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        vpn.deleteVpnProfile(TEST_VPN_PKG);

        verify(mVpnProfileStore)
                .remove(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
    }

    @Test
    public void testDeleteVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpnAndSetupUidChecks(
                        RESTRICTED_PROFILE_A, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.deleteVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testGetVpnProfilePrivileged() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(new VpnProfile("").encode());

        vpn.getVpnProfilePrivileged(TEST_VPN_PKG);

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
    }

    private void verifyPlatformVpnIsActivated(String packageName) {
        verify(mAppOps).noteOpNoThrow(
                eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                eq(Process.myUid()),
                eq(packageName),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
        verify(mAppOps).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER),
                eq(Process.myUid()),
                eq(packageName),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
    }

    private void verifyPlatformVpnIsDeactivated(String packageName) {
        // Add a small delay to double confirm that finishOp is only called once.
        verify(mAppOps, after(100)).finishOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER),
                eq(Process.myUid()),
                eq(packageName),
                eq(null) /* attributionTag */);
    }

    @Test
    public void testStartVpnProfile() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        vpn.startVpnProfile(TEST_VPN_PKG);

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
    }

    @Test
    public void testStartVpnProfileVpnServicePreconsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_VPN);

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        vpn.startVpnProfile(TEST_VPN_PKG);

        // Verify that the ACTIVATE_VPN appop was checked, but no error was thrown.
        verify(mAppOps).noteOpNoThrow(AppOpsManager.OPSTR_ACTIVATE_VPN, Process.myUid(),
                TEST_VPN_PKG, null /* attributionTag */, null /* message */);
    }

    @Test
    public void testStartVpnProfileNotConsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        try {
            vpn.startVpnProfile(TEST_VPN_PKG);
            fail("Expected failure due to no user consent");
        } catch (SecurityException expected) {
        }

        // Verify both appops were checked.
        verify(mAppOps)
                .noteOpNoThrow(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(null) /* attributionTag */,
                        eq(null) /* message */);
        verify(mAppOps).noteOpNoThrow(AppOpsManager.OPSTR_ACTIVATE_VPN, Process.myUid(),
                TEST_VPN_PKG, null /* attributionTag */, null /* message */);

        // Keystore should never have been accessed.
        verify(mVpnProfileStore, never()).get(any());
    }

    @Test
    public void testStartVpnProfileMissingProfile() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG))).thenReturn(null);

        try {
            vpn.startVpnProfile(TEST_VPN_PKG);
            fail("Expected failure due to missing profile");
        } catch (IllegalArgumentException expected) {
        }

        verify(mVpnProfileStore).get(vpn.getProfileNameForPackage(TEST_VPN_PKG));
        verify(mAppOps)
                .noteOpNoThrow(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(null) /* attributionTag */,
                        eq(null) /* message */);
    }

    @Test
    public void testStartVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpnAndSetupUidChecks(
                        RESTRICTED_PROFILE_A, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.startVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testStopVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpnAndSetupUidChecks(
                        RESTRICTED_PROFILE_A, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.stopVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testStartOpAndFinishOpWillBeCalledWhenPlatformVpnIsOnAndOff() throws Exception {
        assumeTrue(SdkLevel.isAtLeastT());
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());
        vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
        // Add a small delay to make sure that startOp is only called once.
        verify(mAppOps, after(100).times(1)).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER),
                eq(Process.myUid()),
                eq(TEST_VPN_PKG),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
        // Check that the startOp is not called with OPSTR_ESTABLISH_VPN_SERVICE.
        verify(mAppOps, never()).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE),
                eq(Process.myUid()),
                eq(TEST_VPN_PKG),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
        vpn.stopVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsDeactivated(TEST_VPN_PKG);
    }

    @Test
    public void testStartOpWithSeamlessHandover() throws Exception {
        assumeTrue(SdkLevel.isAtLeastT());
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_VPN);
        assertTrue(vpn.prepare(TEST_VPN_PKG, null, VpnManager.TYPE_VPN_SERVICE));
        final VpnConfig config = new VpnConfig();
        config.user = "VpnTest";
        config.addresses.add(new LinkAddress("192.0.2.2/32"));
        config.mtu = 1450;
        final ResolveInfo resolveInfo = new ResolveInfo();
        final ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = BIND_VPN_SERVICE;
        resolveInfo.serviceInfo = serviceInfo;
        when(mPackageManager.resolveService(any(), anyInt())).thenReturn(resolveInfo);
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        vpn.establish(config);
        verify(mAppOps, times(1)).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE),
                eq(Process.myUid()),
                eq(TEST_VPN_PKG),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
        // Call establish() twice with the same config, it should match seamless handover case and
        // startOp() shouldn't be called again.
        vpn.establish(config);
        verify(mAppOps, times(1)).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE),
                eq(Process.myUid()),
                eq(TEST_VPN_PKG),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
    }

    private void verifyVpnManagerEvent(String sessionKey, String category, int errorClass,
            int errorCode, VpnProfileState... profileState) {
        final Context userContext =
                mContext.createContextAsUser(UserHandle.of(PRIMARY_USER.id), 0 /* flags */);
        final ArgumentCaptor<Intent> intentArgumentCaptor = ArgumentCaptor.forClass(Intent.class);

        final int verifyTimes = (profileState == null) ? 1 : profileState.length;
        verify(userContext, times(verifyTimes)).startService(intentArgumentCaptor.capture());

        for (int i = 0; i < verifyTimes; i++) {
            final Intent intent = intentArgumentCaptor.getAllValues().get(i);
            assertEquals(sessionKey, intent.getStringExtra(VpnManager.EXTRA_SESSION_KEY));
            final Set<String> categories = intent.getCategories();
            assertTrue(categories.contains(category));
            assertEquals(errorClass,
                    intent.getIntExtra(VpnManager.EXTRA_ERROR_CLASS, -1 /* defaultValue */));
            assertEquals(errorCode,
                    intent.getIntExtra(VpnManager.EXTRA_ERROR_CODE, -1 /* defaultValue */));
            if (profileState != null) {
                assertEquals(profileState[i], intent.getParcelableExtra(
                        VpnManager.EXTRA_VPN_PROFILE_STATE, VpnProfileState.class));
            }
        }
        reset(userContext);
    }

    @Test
    public void testVpnManagerEventForUserDeactivated() throws Exception {
        assumeTrue(SdkLevel.isAtLeastT());
        // For security reasons, Vpn#prepare() will check that oldPackage and newPackage are either
        // null or the package of the caller. This test will call Vpn#prepare() to pretend the old
        // VPN is replaced by a new one. But only Settings can change to some other packages, and
        // this is checked with CONTROL_VPN so simulate holding CONTROL_VPN in order to pass the
        // security checks.
        doReturn(PERMISSION_GRANTED).when(mContext).checkCallingOrSelfPermission(CONTROL_VPN);
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        // Test the case that the user deactivates the vpn in vpn app.
        final String sessionKey1 = vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
        vpn.stopVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsDeactivated(TEST_VPN_PKG);
        verifyPowerSaveTempWhitelistApp(TEST_VPN_PKG);
        reset(mDeviceIdleInternal);
        // CATEGORY_EVENT_DEACTIVATED_BY_USER is not an error event, so both of errorClass and
        // errorCode won't be set.
        verifyVpnManagerEvent(sessionKey1, VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER,
                -1 /* errorClass */, -1 /* errorCode */, null /* profileState */);
        reset(mAppOps);

        // Test the case that the user chooses another vpn and the original one is replaced.
        final String sessionKey2 = vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
        vpn.prepare(TEST_VPN_PKG, "com.new.vpn" /* newPackage */, TYPE_VPN_PLATFORM);
        verifyPlatformVpnIsDeactivated(TEST_VPN_PKG);
        verifyPowerSaveTempWhitelistApp(TEST_VPN_PKG);
        reset(mDeviceIdleInternal);
        // CATEGORY_EVENT_DEACTIVATED_BY_USER is not an error event, so both of errorClass and
        // errorCode won't be set.
        verifyVpnManagerEvent(sessionKey2, VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER,
                -1 /* errorClass */, -1 /* errorCode */, null /* profileState */);
    }

    @Test
    public void testVpnManagerEventForAlwaysOnChanged() throws Exception {
        assumeTrue(SdkLevel.isAtLeastT());
        // Calling setAlwaysOnPackage() needs to hold CONTROL_VPN.
        doReturn(PERMISSION_GRANTED).when(mContext).checkCallingOrSelfPermission(CONTROL_VPN);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        // Enable VPN always-on for PKGS[1].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyVpnManagerEvent(null /* sessionKey */,
                VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED, -1 /* errorClass */,
                -1 /* errorCode */, new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, false /* lockdown */));

        // Enable VPN lockdown for PKGS[1].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyVpnManagerEvent(null /* sessionKey */,
                VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED, -1 /* errorClass */,
                -1 /* errorCode */, new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, true /* lockdown */));

        // Disable VPN lockdown for PKGS[1].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyVpnManagerEvent(null /* sessionKey */,
                VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED, -1 /* errorClass */,
                -1 /* errorCode */, new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, false /* lockdown */));

        // Disable VPN always-on.
        assertTrue(vpn.setAlwaysOnPackage(null, false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyVpnManagerEvent(null /* sessionKey */,
                VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED, -1 /* errorClass */,
                -1 /* errorCode */, new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, false /* alwaysOn */, false /* lockdown */));

        // Enable VPN always-on for PKGS[1] again.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyVpnManagerEvent(null /* sessionKey */,
                VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED, -1 /* errorClass */,
                -1 /* errorCode */, new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, false /* lockdown */));

        // Enable VPN always-on for PKGS[2].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[2], false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[2]);
        reset(mDeviceIdleInternal);
        // PKGS[1] is replaced with PKGS[2].
        // Pass 2 VpnProfileState objects to verifyVpnManagerEvent(), the first one is sent to
        // PKGS[1] to notify PKGS[1] that the VPN always-on is disabled, the second one is sent to
        // PKGS[2] to notify PKGS[2] that the VPN always-on is enabled.
        verifyVpnManagerEvent(null /* sessionKey */,
                VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED, -1 /* errorClass */,
                -1 /* errorCode */, new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, false /* alwaysOn */, false /* lockdown */),
                new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, false /* lockdown */));
    }

    @Test
    public void testReconnectVpnManagerVpnWithAlwaysOnEnabled() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());
        vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);

        // Enable VPN always-on for TEST_VPN_PKG.
        assertTrue(vpn.setAlwaysOnPackage(TEST_VPN_PKG, false /* lockdown */,
                null /* lockdownAllowlist */));

        // Reset to verify next startVpnProfile.
        reset(mAppOps);

        vpn.stopVpnProfile(TEST_VPN_PKG);

        // Reconnect the vpn with different package will cause exception.
        assertThrows(SecurityException.class, () -> vpn.startVpnProfile(PKGS[0]));

        // Reconnect the vpn again with the vpn always on package w/o exception.
        vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
    }

    @Test
    public void testSetPackageAuthorizationVpnService() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, VpnManager.TYPE_VPN_SERVICE));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_ALLOWED));
    }

    @Test
    public void testSetPackageAuthorizationPlatformVpn() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, TYPE_VPN_PLATFORM));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_ALLOWED));
    }

    @Test
    public void testSetPackageAuthorizationRevokeAuthorization() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, VpnManager.TYPE_VPN_NONE));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_IGNORED));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_IGNORED));
    }

    private NetworkCallback triggerOnAvailableAndGetCallback() throws Exception {
        final ArgumentCaptor<NetworkCallback> networkCallbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS))
                .requestNetwork(any(), networkCallbackCaptor.capture());

        // onAvailable() will trigger onDefaultNetworkChanged(), so NetdUtils#setInterfaceUp will be
        // invoked. Set the return value of INetd#interfaceGetCfg to prevent NullPointerException.
        final InterfaceConfigurationParcel config = new InterfaceConfigurationParcel();
        config.flags = new String[] {IF_STATE_DOWN};
        when(mNetd.interfaceGetCfg(anyString())).thenReturn(config);
        final NetworkCallback cb = networkCallbackCaptor.getValue();
        cb.onAvailable(TEST_NETWORK);
        return cb;
    }

    private void verifyInterfaceSetCfgWithFlags(String flag) throws Exception {
        // Add a timeout for waiting for interfaceSetCfg to be called.
        verify(mNetd, timeout(TEST_TIMEOUT_MS)).interfaceSetCfg(argThat(
                config -> Arrays.asList(config.flags).contains(flag)));
    }

    private void doTestPlatformVpnWithException(IkeException exception,
            String category, int errorType, int errorCode) throws Exception {
        final ArgumentCaptor<IkeSessionCallback> captor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);

        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        final String sessionKey = vpn.startVpnProfile(TEST_VPN_PKG);
        final NetworkCallback cb = triggerOnAvailableAndGetCallback();

        verifyInterfaceSetCfgWithFlags(IF_STATE_UP);

        // Wait for createIkeSession() to be called before proceeding in order to ensure consistent
        // state
        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS))
                .createIkeSession(any(), any(), any(), any(), captor.capture(), any());
        reset(mIkev2SessionCreator);
        final IkeSessionCallback ikeCb = captor.getValue();
        ikeCb.onClosedWithException(exception);

        verifyPowerSaveTempWhitelistApp(TEST_VPN_PKG);
        reset(mDeviceIdleInternal);
        verifyVpnManagerEvent(sessionKey, category, errorType, errorCode, null /* profileState */);
        if (errorType == VpnManager.ERROR_CLASS_NOT_RECOVERABLE) {
            verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS))
                    .unregisterNetworkCallback(eq(cb));
        } else if (errorType == VpnManager.ERROR_CLASS_RECOVERABLE) {
            int retryIndex = 0;
            final IkeSessionCallback ikeCb2 = verifyRetryAndGetNewIkeCb(retryIndex++);

            ikeCb2.onClosedWithException(exception);
            verifyRetryAndGetNewIkeCb(retryIndex++);
        }
    }

    private IkeSessionCallback verifyRetryAndGetNewIkeCb(int retryIndex) {
        final ArgumentCaptor<Runnable> runnableCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        final ArgumentCaptor<IkeSessionCallback> ikeCbCaptor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);

        // Verify retry is scheduled
        final long expectedDelay = mTestDeps.getNextRetryDelaySeconds(retryIndex);
        verify(mExecutor).schedule(runnableCaptor.capture(), eq(expectedDelay), any());

        // Mock the event of firing the retry task
        runnableCaptor.getValue().run();

        verify(mIkev2SessionCreator)
                .createIkeSession(any(), any(), any(), any(), ikeCbCaptor.capture(), any());

        // Forget the mIkev2SessionCreator#createIkeSession call and mExecutor#schedule call
        // for the next retry verification
        resetIkev2SessionCreator(mIkeSessionWrapper);
        resetExecutor(mScheduledFuture);

        return ikeCbCaptor.getValue();
    }

    @Test
    public void testStartPlatformVpnAuthenticationFailed() throws Exception {
        final IkeProtocolException exception = mock(IkeProtocolException.class);
        final int errorCode = IkeProtocolException.ERROR_TYPE_AUTHENTICATION_FAILED;
        when(exception.getErrorType()).thenReturn(errorCode);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_IKE_ERROR, VpnManager.ERROR_CLASS_NOT_RECOVERABLE,
                errorCode);
    }

    @Test
    public void testStartPlatformVpnFailedWithRecoverableError() throws Exception {
        final IkeProtocolException exception = mock(IkeProtocolException.class);
        final int errorCode = IkeProtocolException.ERROR_TYPE_TEMPORARY_FAILURE;
        when(exception.getErrorType()).thenReturn(errorCode);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_IKE_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE, errorCode);
    }

    @Test
    public void testStartPlatformVpnFailedWithUnknownHostException() throws Exception {
        final IkeNonProtocolException exception = mock(IkeNonProtocolException.class);
        final UnknownHostException unknownHostException = new UnknownHostException();
        final int errorCode = VpnManager.ERROR_CODE_NETWORK_UNKNOWN_HOST;
        when(exception.getCause()).thenReturn(unknownHostException);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_NETWORK_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE,
                errorCode);
    }

    @Test
    public void testStartPlatformVpnFailedWithIkeTimeoutException() throws Exception {
        final IkeNonProtocolException exception = mock(IkeNonProtocolException.class);
        final IkeTimeoutException ikeTimeoutException =
                new IkeTimeoutException("IkeTimeoutException");
        final int errorCode = VpnManager.ERROR_CODE_NETWORK_PROTOCOL_TIMEOUT;
        when(exception.getCause()).thenReturn(ikeTimeoutException);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_NETWORK_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE,
                errorCode);
    }

    @Test
    public void testStartPlatformVpnFailedWithIkeNetworkLostException() throws Exception {
        final IkeNetworkLostException exception = new IkeNetworkLostException(
                new Network(100));
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_NETWORK_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE,
                VpnManager.ERROR_CODE_NETWORK_LOST);
    }

    @Test
    public void testStartPlatformVpnFailedWithIOException() throws Exception {
        final IkeNonProtocolException exception = mock(IkeNonProtocolException.class);
        final IOException ioException = new IOException();
        final int errorCode = VpnManager.ERROR_CODE_NETWORK_IO;
        when(exception.getCause()).thenReturn(ioException);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_NETWORK_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE,
                errorCode);
    }

    @Test
    public void testStartPlatformVpnIllegalArgumentExceptionInSetup() throws Exception {
        when(mIkev2SessionCreator.createIkeSession(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException());
        final Vpn vpn = startLegacyVpn(createVpn(PRIMARY_USER.id), mVpnProfile);
        final NetworkCallback cb = triggerOnAvailableAndGetCallback();

        verifyInterfaceSetCfgWithFlags(IF_STATE_UP);

        // Wait for createIkeSession() to be called before proceeding in order to ensure consistent
        // state
        verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS)).unregisterNetworkCallback(eq(cb));
        assertEquals(LegacyVpnInfo.STATE_FAILED, vpn.getLegacyVpnInfo().state);
    }

    @Test
    public void testVpnManagerEventWillNotBeSentToSettingsVpn() throws Exception {
        startLegacyVpn(createVpn(PRIMARY_USER.id), mVpnProfile);
        triggerOnAvailableAndGetCallback();

        verifyInterfaceSetCfgWithFlags(IF_STATE_UP);

        final IkeNonProtocolException exception = mock(IkeNonProtocolException.class);
        final IkeTimeoutException ikeTimeoutException =
                new IkeTimeoutException("IkeTimeoutException");
        when(exception.getCause()).thenReturn(ikeTimeoutException);

        final ArgumentCaptor<IkeSessionCallback> captor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);
        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS))
                .createIkeSession(any(), any(), any(), any(), captor.capture(), any());
        final IkeSessionCallback ikeCb = captor.getValue();
        ikeCb.onClosedWithException(exception);

        final Context userContext =
                mContext.createContextAsUser(UserHandle.of(PRIMARY_USER.id), 0 /* flags */);
        verify(userContext, never()).startService(any());
    }

    private void setAndVerifyAlwaysOnPackage(Vpn vpn, int uid, boolean lockdownEnabled) {
        assertTrue(vpn.setAlwaysOnPackage(TEST_VPN_PKG, lockdownEnabled, null));

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
        verify(mAppOps).setMode(
                eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN), eq(uid), eq(TEST_VPN_PKG),
                eq(AppOpsManager.MODE_ALLOWED));

        verify(mSystemServices).settingsSecurePutStringForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_APP), eq(TEST_VPN_PKG), eq(PRIMARY_USER.id));
        verify(mSystemServices).settingsSecurePutIntForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN), eq(lockdownEnabled ? 1 : 0),
                eq(PRIMARY_USER.id));
        verify(mSystemServices).settingsSecurePutStringForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST), eq(""), eq(PRIMARY_USER.id));
    }

    @Test
    public void testSetAndStartAlwaysOnVpn() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        setMockedUsers(PRIMARY_USER);

        // UID checks must return a different UID; otherwise it'll be treated as already prepared.
        final int uid = Process.myUid() + 1;
        when(mPackageManager.getPackageUidAsUser(eq(TEST_VPN_PKG), anyInt()))
                .thenReturn(uid);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        setAndVerifyAlwaysOnPackage(vpn, uid, false);
        assertTrue(vpn.startAlwaysOnVpn());

        // TODO: Test the Ikev2VpnRunner started up properly. Relies on utility methods added in
        // a subsequent CL.
    }

    private Vpn startLegacyVpn(final Vpn vpn, final VpnProfile vpnProfile) throws Exception {
        setMockedUsers(PRIMARY_USER);

        // Dummy egress interface
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(EGRESS_IFACE);

        final RouteInfo defaultRoute = new RouteInfo(new IpPrefix(Inet4Address.ANY, 0),
                        InetAddresses.parseNumericAddress("192.0.2.0"), EGRESS_IFACE);
        lp.addRoute(defaultRoute);

        vpn.startLegacyVpn(vpnProfile, EGRESS_NETWORK, lp);
        return vpn;
    }

    private IkeSessionConnectionInfo createIkeConnectInfo() {
        return new IkeSessionConnectionInfo(TEST_VPN_CLIENT_IP, TEST_VPN_SERVER_IP, TEST_NETWORK);
    }

    private IkeSessionConnectionInfo createIkeConnectInfo_2() {
        return new IkeSessionConnectionInfo(
                TEST_VPN_CLIENT_IP_2, TEST_VPN_SERVER_IP_2, TEST_NETWORK_2);
    }

    private IkeSessionConfiguration createIkeConfig(
            IkeSessionConnectionInfo ikeConnectInfo, boolean isMobikeEnabled) {
        final IkeSessionConfiguration.Builder builder =
                new IkeSessionConfiguration.Builder(ikeConnectInfo);

        if (isMobikeEnabled) {
            builder.addIkeExtension(EXTENSION_TYPE_MOBIKE);
        }

        return builder.build();
    }

    private ChildSessionConfiguration createChildConfig() {
        return new ChildSessionConfiguration.Builder(Arrays.asList(IN_TS), Arrays.asList(OUT_TS))
                .addInternalAddress(new LinkAddress(TEST_VPN_INTERNAL_IP, IP4_PREFIX_LEN))
                .addInternalDnsServer(TEST_VPN_INTERNAL_DNS)
                .build();
    }

    private IpSecTransform createIpSecTransform() {
        return new IpSecTransform(mContext, new IpSecConfig());
    }

    private void verifyApplyTunnelModeTransforms(int expectedTimes) throws Exception {
        verify(mIpSecService, times(expectedTimes)).applyTunnelModeTransform(
                eq(TEST_TUNNEL_RESOURCE_ID), eq(IpSecManager.DIRECTION_IN),
                anyInt(), anyString());
        verify(mIpSecService, times(expectedTimes)).applyTunnelModeTransform(
                eq(TEST_TUNNEL_RESOURCE_ID), eq(IpSecManager.DIRECTION_OUT),
                anyInt(), anyString());
    }

    private Pair<IkeSessionCallback, ChildSessionCallback> verifyCreateIkeAndCaptureCbs()
            throws Exception {
        final ArgumentCaptor<IkeSessionCallback> ikeCbCaptor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);
        final ArgumentCaptor<ChildSessionCallback> childCbCaptor =
                ArgumentCaptor.forClass(ChildSessionCallback.class);

        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS)).createIkeSession(
                any(), any(), any(), any(), ikeCbCaptor.capture(), childCbCaptor.capture());

        return new Pair<>(ikeCbCaptor.getValue(), childCbCaptor.getValue());
    }

    private static class PlatformVpnSnapshot {
        public final Vpn vpn;
        public final NetworkCallback nwCb;
        public final IkeSessionCallback ikeCb;
        public final ChildSessionCallback childCb;

        PlatformVpnSnapshot(Vpn vpn, NetworkCallback nwCb,
                IkeSessionCallback ikeCb, ChildSessionCallback childCb) {
            this.vpn = vpn;
            this.nwCb = nwCb;
            this.ikeCb = ikeCb;
            this.childCb = childCb;
        }
    }

    private PlatformVpnSnapshot verifySetupPlatformVpn(IkeSessionConfiguration ikeConfig)
            throws Exception {
        doReturn(mMockNetworkAgent).when(mTestDeps)
                .newNetworkAgent(
                        any(), any(), anyString(), any(), any(), any(), any(), any());

        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        vpn.startVpnProfile(TEST_VPN_PKG);
        final NetworkCallback nwCb = triggerOnAvailableAndGetCallback();

        // Mock the setup procedure by firing callbacks
        final Pair<IkeSessionCallback, ChildSessionCallback> cbPair =
                verifyCreateIkeAndCaptureCbs();
        final IkeSessionCallback ikeCb = cbPair.first;
        final ChildSessionCallback childCb = cbPair.second;

        ikeCb.onOpened(ikeConfig);
        childCb.onIpSecTransformCreated(createIpSecTransform(), IpSecManager.DIRECTION_IN);
        childCb.onIpSecTransformCreated(createIpSecTransform(), IpSecManager.DIRECTION_OUT);
        childCb.onOpened(createChildConfig());

        // Verification VPN setup
        verifyApplyTunnelModeTransforms(1);

        ArgumentCaptor<LinkProperties> lpCaptor = ArgumentCaptor.forClass(LinkProperties.class);
        ArgumentCaptor<NetworkCapabilities> ncCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mTestDeps).newNetworkAgent(
                any(), any(), anyString(), ncCaptor.capture(), lpCaptor.capture(),
                any(), any(), any());

        // Check LinkProperties
        final LinkProperties lp = lpCaptor.getValue();
        final List<RouteInfo> expectedRoutes = Arrays.asList(
                new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null /*gateway*/,
                        TEST_IFACE_NAME, RouteInfo.RTN_UNICAST),
                new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null /*gateway*/,
                        TEST_IFACE_NAME, RTN_UNREACHABLE));
        assertEquals(expectedRoutes, lp.getRoutes());

        // Check internal addresses
        final List<LinkAddress> expectedAddresses =
                Arrays.asList(new LinkAddress(TEST_VPN_INTERNAL_IP, IP4_PREFIX_LEN));
        assertEquals(expectedAddresses, lp.getLinkAddresses());

        // Check internal DNS
        assertEquals(Arrays.asList(TEST_VPN_INTERNAL_DNS), lp.getDnsServers());

        // Check NetworkCapabilities
        assertEquals(Arrays.asList(TEST_NETWORK), ncCaptor.getValue().getUnderlyingNetworks());

        return new PlatformVpnSnapshot(vpn, nwCb, ikeCb, childCb);
    }

    @Test
    public void testStartPlatformVpn() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));
        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    @Test
    public void testStartPlatformVpnMobility_mobikeEnabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));

        // Mock network loss and verify a cleanup task is scheduled
        vpnSnapShot.nwCb.onLost(TEST_NETWORK);
        verify(mExecutor).schedule(any(Runnable.class), anyLong(), any());

        // Mock new network comes up and the cleanup task is cancelled
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);
        verify(mScheduledFuture).cancel(anyBoolean());

        // Verify MOBIKE is triggered
        verify(mIkeSessionWrapper).setNetwork(TEST_NETWORK_2);

        // Mock the MOBIKE procedure
        vpnSnapShot.ikeCb.onIkeSessionConnectionInfoChanged(createIkeConnectInfo_2());
        vpnSnapShot.childCb.onIpSecTransformsMigrated(
                createIpSecTransform(), createIpSecTransform());

        verify(mIpSecService).setNetworkForTunnelInterface(
                eq(TEST_TUNNEL_RESOURCE_ID), eq(TEST_NETWORK_2), anyString());

        // Expect 2 times: one for initial setup and one for MOBIKE
        verifyApplyTunnelModeTransforms(2);

        // Verify mNetworkCapabilities and mNetworkAgent are updated
        assertEquals(
                Collections.singletonList(TEST_NETWORK_2),
                vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks());
        verify(mMockNetworkAgent)
                .doSetUnderlyingNetworks(Collections.singletonList(TEST_NETWORK_2));

        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    @Test
    public void testStartPlatformVpnReestablishes_mobikeDisabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), false /* isMobikeEnabled */));

        // Forget the first IKE creation to be prepared to capture callbacks of the second
        // IKE session
        resetIkev2SessionCreator(mock(Vpn.IkeSessionWrapper.class));

        // Mock network switch
        vpnSnapShot.nwCb.onLost(TEST_NETWORK);
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);

        // Verify the old IKE Session is killed
        verify(mIkeSessionWrapper).kill();

        // Capture callbacks of the new IKE Session
        final Pair<IkeSessionCallback, ChildSessionCallback> cbPair =
                verifyCreateIkeAndCaptureCbs();
        final IkeSessionCallback ikeCb = cbPair.first;
        final ChildSessionCallback childCb = cbPair.second;

        // Mock the IKE Session setup
        ikeCb.onOpened(createIkeConfig(createIkeConnectInfo_2(), false /* isMobikeEnabled */));

        childCb.onIpSecTransformCreated(createIpSecTransform(), IpSecManager.DIRECTION_IN);
        childCb.onIpSecTransformCreated(createIpSecTransform(), IpSecManager.DIRECTION_OUT);
        childCb.onOpened(createChildConfig());

        // Expect 2 times since there have been two Session setups
        verifyApplyTunnelModeTransforms(2);

        // Verify mNetworkCapabilities and mNetworkAgent are updated
        assertEquals(
                Collections.singletonList(TEST_NETWORK_2),
                vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks());
        verify(mMockNetworkAgent)
                .doSetUnderlyingNetworks(Collections.singletonList(TEST_NETWORK_2));

        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    private void verifyHandlingNetworkLoss() throws Exception {
        final ArgumentCaptor<LinkProperties> lpCaptor =
                ArgumentCaptor.forClass(LinkProperties.class);
        verify(mMockNetworkAgent).doSendLinkProperties(lpCaptor.capture());
        final LinkProperties lp = lpCaptor.getValue();

        assertNull(lp.getInterfaceName());
        final List<RouteInfo> expectedRoutes = Arrays.asList(
                new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null /*gateway*/,
                        null /*iface*/, RTN_UNREACHABLE),
                new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null /*gateway*/,
                        null /*iface*/, RTN_UNREACHABLE));
        assertEquals(expectedRoutes, lp.getRoutes());
    }

    @Test
    public void testStartPlatformVpnHandlesNetworkLoss_mobikeEnabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), false /* isMobikeEnabled */));

        // Forget the #sendLinkProperties during first setup.
        reset(mMockNetworkAgent);

        final ArgumentCaptor<Runnable> runnableCaptor =
                ArgumentCaptor.forClass(Runnable.class);

        // Mock network loss
        vpnSnapShot.nwCb.onLost(TEST_NETWORK);

        // Mock the grace period expires
        verify(mExecutor).schedule(runnableCaptor.capture(), anyLong(), any());
        runnableCaptor.getValue().run();

        verifyHandlingNetworkLoss();
    }

    @Test
    public void testStartPlatformVpnHandlesNetworkLoss_mobikeDisabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), false /* isMobikeEnabled */));

        // Forget the #sendLinkProperties during first setup.
        reset(mMockNetworkAgent);

        // Mock network loss
        vpnSnapShot.nwCb.onLost(TEST_NETWORK);

        verifyHandlingNetworkLoss();
    }

    @Test
    public void testStartRacoonNumericAddress() throws Exception {
        startRacoon("1.2.3.4", "1.2.3.4");
    }

    @Test
    public void testStartRacoonHostname() throws Exception {
        startRacoon("hostname", "5.6.7.8"); // address returned by deps.resolve
    }

    @Test
    public void testStartPptp() throws Exception {
        startPptp(true /* useMppe */);
    }

    @Test
    public void testStartPptp_NoMppe() throws Exception {
        startPptp(false /* useMppe */);
    }

    private void assertTransportInfoMatches(NetworkCapabilities nc, int type) {
        assertNotNull(nc);
        VpnTransportInfo ti = (VpnTransportInfo) nc.getTransportInfo();
        assertNotNull(ti);
        assertEquals(type, ti.getType());
    }

    private void startPptp(boolean useMppe) throws Exception {
        final VpnProfile profile = new VpnProfile("testProfile" /* key */);
        profile.type = VpnProfile.TYPE_PPTP;
        profile.name = "testProfileName";
        profile.username = "userName";
        profile.password = "thePassword";
        profile.server = "192.0.2.123";
        profile.mppe = useMppe;

        doReturn(new Network[] { new Network(101) }).when(mConnectivityManager).getAllNetworks();
        doReturn(new Network(102)).when(mConnectivityManager).registerNetworkAgent(any(), any(),
                any(), any(), any(), any(), anyInt());

        final Vpn vpn = startLegacyVpn(createVpn(PRIMARY_USER.id), profile);
        final TestDeps deps = (TestDeps) vpn.mDeps;

        testAndCleanup(() -> {
            final String[] mtpdArgs = deps.mtpdArgs.get(10, TimeUnit.SECONDS);
            final String[] argsPrefix = new String[]{
                    EGRESS_IFACE, "pptp", profile.server, "1723", "name", profile.username,
                    "password", profile.password, "linkname", "vpn", "refuse-eap", "nodefaultroute",
                    "usepeerdns", "idle", "1800", "mtu", "1270", "mru", "1270"
            };
            assertArrayEquals(argsPrefix, Arrays.copyOf(mtpdArgs, argsPrefix.length));
            if (useMppe) {
                assertEquals(argsPrefix.length + 2, mtpdArgs.length);
                assertEquals("+mppe", mtpdArgs[argsPrefix.length]);
                assertEquals("-pap", mtpdArgs[argsPrefix.length + 1]);
            } else {
                assertEquals(argsPrefix.length + 1, mtpdArgs.length);
                assertEquals("nomppe", mtpdArgs[argsPrefix.length]);
            }

            verify(mConnectivityManager, timeout(10_000)).registerNetworkAgent(any(), any(),
                    any(), any(), any(), any(), anyInt());
        }, () -> { // Cleanup
                vpn.mVpnRunner.exitVpnRunner();
                deps.getStateFile().delete(); // set to delete on exit, but this deletes it earlier
                vpn.mVpnRunner.join(10_000); // wait for up to 10s for the runner to die and cleanup
            });
    }

    public void startRacoon(final String serverAddr, final String expectedAddr)
            throws Exception {
        final ConditionVariable legacyRunnerReady = new ConditionVariable();
        final VpnProfile profile = new VpnProfile("testProfile" /* key */);
        profile.type = VpnProfile.TYPE_L2TP_IPSEC_PSK;
        profile.name = "testProfileName";
        profile.username = "userName";
        profile.password = "thePassword";
        profile.server = serverAddr;
        profile.ipsecIdentifier = "id";
        profile.ipsecSecret = "secret";
        profile.l2tpSecret = "l2tpsecret";

        when(mConnectivityManager.getAllNetworks())
            .thenReturn(new Network[] { new Network(101) });

        when(mConnectivityManager.registerNetworkAgent(any(), any(), any(), any(),
                any(), any(), anyInt())).thenAnswer(invocation -> {
                    // The runner has registered an agent and is now ready.
                    legacyRunnerReady.open();
                    return new Network(102);
                });
        final Vpn vpn = startLegacyVpn(createVpn(PRIMARY_USER.id), profile);
        final TestDeps deps = (TestDeps) vpn.mDeps;
        try {
            // udppsk and 1701 are the values for TYPE_L2TP_IPSEC_PSK
            assertArrayEquals(
                    new String[] { EGRESS_IFACE, expectedAddr, "udppsk",
                            profile.ipsecIdentifier, profile.ipsecSecret, "1701" },
                    deps.racoonArgs.get(10, TimeUnit.SECONDS));
            // literal values are hardcoded in Vpn.java for mtpd args
            assertArrayEquals(
                    new String[] { EGRESS_IFACE, "l2tp", expectedAddr, "1701", profile.l2tpSecret,
                            "name", profile.username, "password", profile.password,
                            "linkname", "vpn", "refuse-eap", "nodefaultroute", "usepeerdns",
                            "idle", "1800", "mtu", "1270", "mru", "1270" },
                    deps.mtpdArgs.get(10, TimeUnit.SECONDS));

            // Now wait for the runner to be ready before testing for the route.
            ArgumentCaptor<LinkProperties> lpCaptor = ArgumentCaptor.forClass(LinkProperties.class);
            ArgumentCaptor<NetworkCapabilities> ncCaptor =
                    ArgumentCaptor.forClass(NetworkCapabilities.class);
            verify(mConnectivityManager, timeout(10_000)).registerNetworkAgent(any(), any(),
                    lpCaptor.capture(), ncCaptor.capture(), any(), any(), anyInt());

            // In this test the expected address is always v4 so /32.
            // Note that the interface needs to be specified because RouteInfo objects stored in
            // LinkProperties objects always acquire the LinkProperties' interface.
            final RouteInfo expectedRoute = new RouteInfo(new IpPrefix(expectedAddr + "/32"),
                    null, EGRESS_IFACE, RouteInfo.RTN_THROW);
            final List<RouteInfo> actualRoutes = lpCaptor.getValue().getRoutes();
            assertTrue("Expected throw route (" + expectedRoute + ") not found in " + actualRoutes,
                    actualRoutes.contains(expectedRoute));

            assertTransportInfoMatches(ncCaptor.getValue(), VpnManager.TYPE_VPN_LEGACY);
        } finally {
            // Now interrupt the thread, unblock the runner and clean up.
            vpn.mVpnRunner.exitVpnRunner();
            deps.getStateFile().delete(); // set to delete on exit, but this deletes it earlier
            vpn.mVpnRunner.join(10_000); // wait for up to 10s for the runner to die and cleanup
        }
    }

    // Make it public and un-final so as to spy it
    public class TestDeps extends Vpn.Dependencies {
        public final CompletableFuture<String[]> racoonArgs = new CompletableFuture();
        public final CompletableFuture<String[]> mtpdArgs = new CompletableFuture();
        public final File mStateFile;

        private final HashMap<String, Boolean> mRunningServices = new HashMap<>();

        TestDeps() {
            try {
                mStateFile = File.createTempFile("vpnTest", ".tmp");
                mStateFile.deleteOnExit();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isCallerSystem() {
            return true;
        }

        @Override
        public void startService(final String serviceName) {
            mRunningServices.put(serviceName, true);
        }

        @Override
        public void stopService(final String serviceName) {
            mRunningServices.put(serviceName, false);
        }

        @Override
        public boolean isServiceRunning(final String serviceName) {
            return mRunningServices.getOrDefault(serviceName, false);
        }

        @Override
        public boolean isServiceStopped(final String serviceName) {
            return !isServiceRunning(serviceName);
        }

        @Override
        public File getStateFile() {
            return mStateFile;
        }

        @Override
        public PendingIntent getIntentForStatusPanel(Context context) {
            return null;
        }

        @Override
        public void sendArgumentsToDaemon(
                final String daemon, final LocalSocket socket, final String[] arguments,
                final Vpn.RetryScheduler interruptChecker) throws IOException {
            if ("racoon".equals(daemon)) {
                racoonArgs.complete(arguments);
            } else if ("mtpd".equals(daemon)) {
                writeStateFile(arguments);
                mtpdArgs.complete(arguments);
            } else {
                throw new UnsupportedOperationException("Unsupported daemon : " + daemon);
            }
        }

        private void writeStateFile(final String[] arguments) throws IOException {
            mStateFile.delete();
            mStateFile.createNewFile();
            mStateFile.deleteOnExit();
            final BufferedWriter writer = new BufferedWriter(
                    new FileWriter(mStateFile, false /* append */));
            writer.write(EGRESS_IFACE);
            writer.write("\n");
            // addresses
            writer.write("10.0.0.1/24\n");
            // routes
            writer.write("192.168.6.0/24\n");
            // dns servers
            writer.write("192.168.6.1\n");
            // search domains
            writer.write("vpn.searchdomains.com\n");
            // endpoint - intentionally empty
            writer.write("\n");
            writer.flush();
            writer.close();
        }

        @Override
        @NonNull
        public InetAddress resolve(final String endpoint) {
            try {
                // If a numeric IP address, return it.
                return InetAddress.parseNumericAddress(endpoint);
            } catch (IllegalArgumentException e) {
                // Otherwise, return some token IP to test for.
                return InetAddress.parseNumericAddress("5.6.7.8");
            }
        }

        @Override
        public boolean isInterfacePresent(final Vpn vpn, final String iface) {
            return true;
        }

        @Override
        public ParcelFileDescriptor adoptFd(Vpn vpn, int mtu) {
            return new ParcelFileDescriptor(new FileDescriptor());
        }

        @Override
        public int jniCreate(Vpn vpn, int mtu) {
            // Pick a random positive number as fd to return.
            return 345;
        }

        @Override
        public String jniGetName(Vpn vpn, int fd) {
            return TEST_IFACE_NAME;
        }

        @Override
        public int jniSetAddresses(Vpn vpn, String interfaze, String addresses) {
            if (addresses == null) return 0;
            // Return the number of addresses.
            return addresses.split(" ").length;
        }

        @Override
        public void setBlocking(FileDescriptor fd, boolean blocking) {}

        @Override
        public DeviceIdleInternal getDeviceIdleInternal() {
            return mDeviceIdleInternal;
        }

        @Override
        public long getNextRetryDelaySeconds(int retryCount) {
            // Simply return retryCount as the delay seconds for retrying.
            return retryCount;
        }

        @Override
        public ScheduledThreadPoolExecutor newScheduledThreadPoolExecutor() {
            return mExecutor;
        }
    }

    /**
     * Mock some methods of vpn object.
     */
    private Vpn createVpn(@UserIdInt int userId) {
        final Context asUserContext = mock(Context.class, AdditionalAnswers.delegatesTo(mContext));
        doReturn(UserHandle.of(userId)).when(asUserContext).getUser();
        when(mContext.createContextAsUser(eq(UserHandle.of(userId)), anyInt()))
                .thenReturn(asUserContext);
        final TestLooper testLooper = new TestLooper();
        final Vpn vpn = new Vpn(testLooper.getLooper(), mContext, mTestDeps, mNetService,
                mNetd, userId, mVpnProfileStore, mSystemServices, mIkev2SessionCreator);
        verify(mConnectivityManager, times(1)).registerNetworkProvider(argThat(
                provider -> provider.getName().contains("VpnNetworkProvider")
        ));
        return vpn;
    }

    /**
     * Populate {@link #mUserManager} with a list of fake users.
     */
    private void setMockedUsers(UserInfo... users) {
        final Map<Integer, UserInfo> userMap = new ArrayMap<>();
        for (UserInfo user : users) {
            userMap.put(user.id, user);
        }

        /**
         * @see UserManagerService#getUsers(boolean)
         */
        doAnswer(invocation -> {
            final ArrayList<UserInfo> result = new ArrayList<>(users.length);
            for (UserInfo ui : users) {
                if (ui.isEnabled() && !ui.partial) {
                    result.add(ui);
                }
            }
            return result;
        }).when(mUserManager).getAliveUsers();

        doAnswer(invocation -> {
            final int id = (int) invocation.getArguments()[0];
            return userMap.get(id);
        }).when(mUserManager).getUserInfo(anyInt());
    }

    /**
     * Populate {@link #mPackageManager} with a fake packageName-to-UID mapping.
     */
    private void setMockedPackages(final Map<String, Integer> packages) {
        try {
            doAnswer(invocation -> {
                final String appName = (String) invocation.getArguments()[0];
                final int userId = (int) invocation.getArguments()[1];
                Integer appId = packages.get(appName);
                if (appId == null) throw new PackageManager.NameNotFoundException(appName);
                return UserHandle.getUid(userId, appId);
            }).when(mPackageManager).getPackageUidAsUser(anyString(), anyInt());
        } catch (Exception e) {
        }
    }

    private void setMockedNetworks(final Map<Network, NetworkCapabilities> networks) {
        doAnswer(invocation -> {
            final Network network = (Network) invocation.getArguments()[0];
            return networks.get(network);
        }).when(mConnectivityManager).getNetworkCapabilities(any());
    }

    // Need multiple copies of this, but Java's Stream objects can't be reused or
    // duplicated.
    private Stream<String> publicIpV4Routes() {
        return Stream.of(
                "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4",
                "32.0.0.0/3", "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6",
                "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9",
                "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11",
                "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14",
                "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7",
                "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4");
    }

    private Stream<String> publicIpV6Routes() {
        return Stream.of(
                "::/1", "8000::/2", "c000::/3", "e000::/4", "f000::/5", "f800::/6",
                "fe00::/8", "2605:ef80:e:af1d::/64");
    }
}
