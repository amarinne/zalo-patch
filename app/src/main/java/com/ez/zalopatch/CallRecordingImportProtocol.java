package com.ez.zalopatch;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Capability-based Binder protocol for pulling a private Zalo recording descriptor. */
public final class CallRecordingImportProtocol {
    private static final String ZALO_PACKAGE = "com.zing.zalo";
    private static final String DESCRIPTOR =
            "com.ez.zalopatch.CallRecordingImportProtocol";
    private static final String VERIFIER_DESCRIPTOR = DESCRIPTOR + ".Verifier";
    private static final int TRANSACTION_OPEN = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_COMPLETE = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TRANSACTION_ATTEST = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int TRANSACTION_CAPTURE_UID = IBinder.FIRST_CALL_TRANSACTION;

    public interface Completion {
        void onComplete(boolean accepted);
    }

    public static final class OpenedSource {
        public final ParcelFileDescriptor descriptor;
        public final int sourceUid;

        OpenedSource(ParcelFileDescriptor descriptor, int sourceUid) {
            this.descriptor = descriptor;
            this.sourceUid = sourceUid;
        }
    }

    private CallRecordingImportProtocol() {
    }

    // These receivers are exported without a permission because the module and Zalo are not
    // co-signed; this UID/package check substitutes for an unavailable signature permission.
    static boolean callerAllowed(Context context, int sourceUid) {
        if (sourceUid == Process.myUid()) {
            return true;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(sourceUid);
        if (packages == null) {
            return false;
        }
        for (String packageName : packages) {
            if (ZALO_PACKAGE.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    public static IBinder source(File file, Completion completion) {
        return new SourceBinder(file, completion);
    }

    public static IBinder identity() {
        return new SourceBinder(null, null);
    }

    public static int attest(IBinder source) throws RemoteException {
        if (source == null) {
            return -1;
        }
        CallerCapture capture = new CallerCapture();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(capture);
            if (!source.transact(TRANSACTION_ATTEST, data, reply, 0)) {
                return -1;
            }
            reply.readException();
            return capture.uid.get();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static OpenedSource open(IBinder source) throws RemoteException {
        if (source == null) {
            return null;
        }
        CallerCapture capture = new CallerCapture();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(capture);
            if (!source.transact(TRANSACTION_OPEN, data, reply, 0)) {
                return null;
            }
            reply.readException();
            ParcelFileDescriptor descriptor = reply.readInt() == 0
                    ? null : ParcelFileDescriptor.CREATOR.createFromParcel(reply);
            return descriptor == null ? null : new OpenedSource(descriptor, capture.uid.get());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static void complete(IBinder source, boolean accepted) throws RemoteException {
        if (source == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(accepted ? 1 : 0);
            if (source.transact(TRANSACTION_COMPLETE, data, reply, 0)) {
                reply.readException();
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static final class SourceBinder extends Binder {
        private final File file;
        private final Completion completion;
        private final AtomicBoolean completed = new AtomicBoolean();

        SourceBinder(File file, Completion completion) {
            this.file = file;
            this.completion = completion;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            data.enforceInterface(DESCRIPTOR);
            if (code == TRANSACTION_OPEN) {
                verifyCaller(data.readStrongBinder());
                writeDescriptor(reply);
                return true;
            }
            if (code == TRANSACTION_COMPLETE) {
                boolean accepted = data.readInt() != 0;
                if (completed.compareAndSet(false, true) && completion != null) {
                    completion.onComplete(accepted);
                }
                reply.writeNoException();
                return true;
            }
            if (code == TRANSACTION_ATTEST) {
                verifyCaller(data.readStrongBinder());
                reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static void verifyCaller(IBinder verifier) throws RemoteException {
            if (verifier == null) {
                return;
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(VERIFIER_DESCRIPTOR);
                if (verifier.transact(TRANSACTION_CAPTURE_UID, data, reply, 0)) {
                    reply.readException();
                }
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void writeDescriptor(Parcel reply) {
            try {
                ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                        file, ParcelFileDescriptor.MODE_READ_ONLY);
                reply.writeNoException();
                reply.writeInt(1);
                descriptor.writeToParcel(reply, ParcelFileDescriptor.PARCELABLE_WRITE_RETURN_VALUE);
            } catch (FileNotFoundException exception) {
                reply.writeException(exception);
            }
        }
    }

    private static final class CallerCapture extends Binder {
        final AtomicInteger uid = new AtomicInteger(-1);

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(VERIFIER_DESCRIPTOR);
                return true;
            }
            if (code != TRANSACTION_CAPTURE_UID) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(VERIFIER_DESCRIPTOR);
            uid.compareAndSet(-1, Binder.getCallingUid());
            reply.writeNoException();
            return true;
        }
    }
}
