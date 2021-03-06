/* Copyright (c) 2016 Microsoft Corporation. This software is licensed under the MIT License.
 * See the license file delivered with this project for further information.
 */
package io.jxcore.node;

import android.os.CountDownTimer;
import android.util.Log;

import org.thaliproject.p2p.btconnectorlib.ConnectionManager;
import org.thaliproject.p2p.btconnectorlib.DiscoveryManager;
import org.thaliproject.p2p.btconnectorlib.DiscoveryManagerSettings;

import java.util.Date;

/**
 * A handler for the asynchronous start and stop calls.
 */
public class StartStopOperationHandler {
    private static final String TAG = StartStopOperationHandler.class.getName();
    private static final long OPERATION_TIMEOUT_IN_MILLISECONDS = 3000;
    private final ConnectionManager mConnectionManager;
    private final DiscoveryManager mDiscoveryManager;
    private CountDownTimer mOperationTimeoutTimer = null;
    private StartStopOperation mCurrentOperation = null;

    /**
     * Constructor.
     *
     * @param connectionManager The connection manager.
     * @param discoveryManager  The discovery manager.
     */
    public StartStopOperationHandler(ConnectionManager connectionManager, DiscoveryManager discoveryManager) {
        mConnectionManager = connectionManager;
        mDiscoveryManager = discoveryManager;
    }

    /**
     * Cancels the current operation.
     * Note that the callback of the current operations, if one exists, will not be called.
     */
    public synchronized void cancelCurrentOperation() {
        cancelOperationTimeoutTimer();
        mCurrentOperation = null;
    }

    /**
     * Executes a new start operation. Any pending operations will be cancelled.
     *
     * @param startAdvertising If true, will start advertising. If false, will only start listening
     *                         for advertisements.
     * @param callback         The callback to call when we get the operation result.
     */
    public synchronized void executeStartOperation(boolean startAdvertising, JXcoreThaliCallback callback) {
        if (mCurrentOperation != null) {
            Log.w(TAG, "executeStartOperation: Cancelling a pending operation");
            cancelCurrentOperation();
        }

        mCurrentOperation = StartStopOperation.createStartOperation(!startAdvertising, callback);
        executeCurrentOperation();
    }

    /**
     * Executes a new stop operation. Any pending operations will be cancelled.
     *
     * @param stopOnlyListeningForAdvertisements If true, will only stop listening for advertisements.
     *                                           If false, will stop everything.
     * @param callback                           The callback to call when we get the operation result.
     */
    public synchronized void executeStopOperation(
        boolean stopOnlyListeningForAdvertisements, JXcoreThaliCallback callback) {
        if (mCurrentOperation != null) {
            Log.w(TAG, "executeStartOperation: Cancelling a pending operation");
            cancelCurrentOperation();
        }

        mCurrentOperation = StartStopOperation.createStopOperation(stopOnlyListeningForAdvertisements, callback);
        executeCurrentOperation();
    }

    /**
     * Calls callback of the current operation if the current operation is successful (the current state matches the expected
     * outcome).
     */
    public synchronized void processCurrentOperationStatus() {
        if (mCurrentOperation != null && isTargetState(mCurrentOperation) == null) {
            Log.d(TAG, "processCurrentOperationStatus: Operation successfully executed");
            cancelOperationTimeoutTimer();
            mCurrentOperation.getCallback().callOnStartStopCallback(null);
            mCurrentOperation = null;
        }
    }

    /**
     * Executes the current operation.
     */
    private synchronized void executeCurrentOperation() {
        if (mCurrentOperation == null) {
            // No operation to execute
            return;
        }

        if (mCurrentOperation.isStartOperation()
            && !mCurrentOperation.shouldAffectListeningToAdvertisementsOnly()) {
            updateBeaconAdExtraInformation();
        }

        if (isTargetState(mCurrentOperation) == null) {
            // The current state already matches the desired outcome of this operation so it is
            // pointless to execute this
            Log.v(TAG, "executeCurrentOperation: The current state already matches the desired outcome of this operation, skipping...");
            mCurrentOperation.getCallback().callOnStartStopCallback(null);
            mCurrentOperation = null;
        } else {
            Log.v(TAG, "executeCurrentOperation: Executing: " + mCurrentOperation.toString());
            final boolean shouldAffectListeningToAdvertisementsOnly =
                mCurrentOperation.shouldAffectListeningToAdvertisementsOnly();

            if (mCurrentOperation.isStartOperation()) {
                // Connection manager shouldn't be started if we want to listen to *advertisements* only
                if (!shouldAffectListeningToAdvertisementsOnly
                    && !mConnectionManager.startListeningForIncomingConnections()) {
                    final String errorMessage = "Failed to start the connection manager (Bluetooth connection listener)";
                    Log.e(TAG, "executeCurrentOperation: " + errorMessage);
                    mCurrentOperation.getCallback().callOnStartStopCallback(errorMessage);
                    mCurrentOperation = null;
                    return;
                }
                boolean shouldAdvertise = !shouldAffectListeningToAdvertisementsOnly;
                boolean shouldDiscovery = shouldAffectListeningToAdvertisementsOnly;

                if (shouldAffectListeningToAdvertisementsOnly) {
                    shouldAdvertise = mDiscoveryManager.isAdvertising();
                } else {
                    shouldDiscovery = mDiscoveryManager.isDiscovering();
                }
                if (!mDiscoveryManager.start(shouldDiscovery, shouldAdvertise)) {
                    final String errorMessage = "Failed to start the discovery manager";
                    Log.e(TAG, "executeCurrentOperation: " + errorMessage);
                    mCurrentOperation.getCallback().callOnStartStopCallback(errorMessage);
                    mCurrentOperation = null;
                }
            } else {
                // Is stop operation
                if (shouldAffectListeningToAdvertisementsOnly) {
                    // Should only stop listening to advertisements
                    mDiscoveryManager.stopDiscovery();
                } else {
                    // Should stop everything
                    mConnectionManager.stopListeningForIncomingConnections();
                    mConnectionManager.cancelAllConnectionAttempts();
                    mDiscoveryManager.stop();
                }
            }
        }

        if (mCurrentOperation != null) {
            cancelOperationTimeoutTimer();

            mCurrentOperation.setOperationExecutedTime(new Date().getTime());

            mOperationTimeoutTimer = new CountDownTimer(
                OPERATION_TIMEOUT_IN_MILLISECONDS, OPERATION_TIMEOUT_IN_MILLISECONDS) {
                @Override
                public void onTick(long l) {
                    // Not used
                }

                @Override
                public void onFinish() {
                    if (mCurrentOperation != null) {
                        String errorMessage = "Operation timeout, state error: " + isTargetState(mCurrentOperation);
                        Log.d(TAG, errorMessage);
                        mCurrentOperation.getCallback().callOnStartStopCallback(errorMessage);
                        mCurrentOperation = null;
                        mOperationTimeoutTimer = null;
                    }
                }
            };

            mOperationTimeoutTimer.start();
        }
    }

    private void cancelOperationTimeoutTimer() {
        if (mOperationTimeoutTimer != null) {
            mOperationTimeoutTimer.cancel();
            mOperationTimeoutTimer = null;
        }
    }

    /**
     * Checks if the current states match the expected outcome of the given operation after executed.
     *
     * @param startStopOperation The operation.
     * @return Null, if the states match the expected outcome. A string with an error message otherwise.
     */
    private String isTargetState(StartStopOperation startStopOperation) {
        return startStopOperation.isTargetState(
            mConnectionManager.getState(),
            mDiscoveryManager.getState(),
            mDiscoveryManager.isDiscovering(),
            mDiscoveryManager.isAdvertising());
    }

    /**
     * Updates the extra information of the beacon advertisement to notify the listeners that we
     * have new information. This affects the peer ID given to the node layer.
     */
    private void updateBeaconAdExtraInformation() {
        DiscoveryManagerSettings discoveryManagerSettings = DiscoveryManagerSettings.getInstance(null);
        int extraInformation = discoveryManagerSettings.getBeaconAdExtraInformation() + 1;
        if (extraInformation > 255) {
            extraInformation = 0;
        }
        Log.i(TAG, "updateBeaconAdExtraInformation: New value: " + extraInformation);
        discoveryManagerSettings.setBeaconAdExtraInformation(extraInformation);
    }
}
