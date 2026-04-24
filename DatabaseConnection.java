import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection{

    public static Connection getConnection() throws SQLException {
        String url = "jdbc:oracle://system:oracle123@localhost:1521/XEPDB1";
        String username = "system";
        String password = "oracle123";

        return DriverManager.getConnection(url, username , password);
    }
}
