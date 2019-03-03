import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteCall extends Remote {
    public FileInfo getVersion(String path, FileHandling.OpenOption o) throws RemoteException;
    public Bus sendToProxy(FileInfo fi, long offset) throws RemoteException;
    public void updateFromProxy(long offset, Bus bus, String path) throws RemoteException;
    public int unlinkFile(String path) throws RemoteException;
}
