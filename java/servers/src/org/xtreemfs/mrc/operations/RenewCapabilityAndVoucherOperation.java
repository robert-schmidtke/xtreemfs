/*
 * Copyright (c) 2015 by Robert Bärhold,
 *               Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.mrc.operations;

import org.xtreemfs.common.Capability;
import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.POSIXErrno;
import org.xtreemfs.mrc.MRCRequest;
import org.xtreemfs.mrc.MRCRequestDispatcher;
import org.xtreemfs.mrc.UserException;
import org.xtreemfs.mrc.database.AtomicDBUpdate;
import org.xtreemfs.mrc.database.DatabaseException;
import org.xtreemfs.mrc.database.DatabaseException.ExceptionType;
import org.xtreemfs.mrc.database.StorageManager;
import org.xtreemfs.mrc.metadata.FileMetadata;
import org.xtreemfs.mrc.quota.QuotaFileInformation;
import org.xtreemfs.mrc.quota.VoucherManager;
import org.xtreemfs.mrc.utils.MRCHelper.GlobalFileIdResolver;
import org.xtreemfs.pbrpc.generatedinterfaces.MRC.xtreemfs_renew_capabilityRequest;

/**
 * This operation is based on the RenewOperation and extends its behavior with voucher handling
 */
public class RenewCapabilityAndVoucherOperation extends MRCOperation {

    public final boolean renewTimedOutCaps;

    public RenewCapabilityAndVoucherOperation(MRCRequestDispatcher master) {
        super(master);
        renewTimedOutCaps = master.getConfig().isRenewTimedOutCaps();
    }

    @Override
    public void startRequest(MRCRequest rq) throws Throwable {

        final xtreemfs_renew_capabilityRequest renewCapabilityRequest = (xtreemfs_renew_capabilityRequest) rq
                .getRequestArgs();

        // perform master redirect if necessary due to DB operation
        if (master.getReplMasterUUID() != null
                && !master.getReplMasterUUID().equals(master.getConfig().getUUID().toString()))
            throw new DatabaseException(ExceptionType.REDIRECT);

        // create a capability object to verify the capability
        Capability cap = new Capability(renewCapabilityRequest.getXcap(), master.getConfig().getCapabilitySecret());

        Logging.logMessage(Logging.LEVEL_DEBUG, this,
                "Renew capability for file: " + cap.getFileId() + " and client: " + cap.getClientIdentity() + ". "
                        + "Increase voucher on renew: " + renewCapabilityRequest.getIncreaseVoucher());

        // check whether the capability has a valid signature
        if (!cap.hasValidSignature())
            throw new UserException(POSIXErrno.POSIX_ERROR_EPERM, cap + " does not have a valid signature");

        // check whether the capability has expired
        if (cap.hasExpired() && !renewTimedOutCaps)
            throw new UserException(POSIXErrno.POSIX_ERROR_EPERM, cap + " has expired");

        // get new voucher, if capability are still valid for vouchers
        long newExpireMs = TimeSync.getGlobalTime() + master.getConfig().getCapabilityTimeout() * 1000;
        long voucherSize = cap.getVoucherSize();

        if (VoucherManager.checkManageableAccess(cap.getAccessMode())) {

            GlobalFileIdResolver globalFileIdResolver = new GlobalFileIdResolver(cap.getFileId());
            StorageManager sMan = master.getVolumeManager().getStorageManager(globalFileIdResolver.getVolumeId());
            AtomicDBUpdate update = sMan.createAtomicDBUpdate(null, null);

            FileMetadata metadata = sMan.getMetadata(globalFileIdResolver.getLocalFileId());

            if(metadata != null) {
                QuotaFileInformation quotaFileInformation = new QuotaFileInformation(globalFileIdResolver.getVolumeId(),
                        metadata);

                if (renewCapabilityRequest.getIncreaseVoucher()) {
                    voucherSize = master.getMrcVoucherManager().checkAndRenewVoucher(quotaFileInformation,
                            cap.getClientIdentity(), cap.getVoucherSize(), cap.getExpireMs(), newExpireMs, update);
                } else {
                    voucherSize = master.getMrcVoucherManager().addRenewedTimestamp(quotaFileInformation,
                            cap.getClientIdentity(),
                            cap.getExpireMs(), newExpireMs, update);
                }

                update.execute();
            } else rq.setError(RPC.ErrorType.INTERNAL_SERVER_ERROR, "Error while renewing XCap");
        }

        Capability newCap = new Capability(cap.getFileId(), cap.getAccessMode(), master.getConfig()
                .getCapabilityTimeout(), TimeSync.getGlobalTime() / 1000 + master.getConfig().getCapabilityTimeout(),
                cap.getClientIdentity(), cap.getEpochNo(), cap.isReplicateOnClose(), cap.getSnapConfig(),
                cap.getSnapTimestamp(), cap.getTraceConfig().getTraceRequests(), cap.getTraceConfig().getTracingPolicy(),
                cap.getTraceConfig().getTracingPolicyConfig(), voucherSize, newExpireMs,
                master.getConfig().getCapabilitySecret());

        // set the response
        rq.setResponse(newCap.getXCap());
        finishRequest(rq);
    }

}
