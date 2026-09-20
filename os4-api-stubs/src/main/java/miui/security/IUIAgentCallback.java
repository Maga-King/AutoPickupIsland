package miui.security;
import android.os.Bundle;
import android.os.IInterface;
import android.os.RemoteException;
public interface IUIAgentCallback extends IInterface {
    void onResult(Bundle bundle) throws RemoteException;
}
