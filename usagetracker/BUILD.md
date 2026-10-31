# Building Usage Tracker

This JavaFX application can be built for Windows, Mac, and Linux platforms.

## Automatic Platform Detection

Maven will automatically detect your platform and use the appropriate profile:

```bash
mvn clean package
```

The shaded JAR will be created with a platform-specific name in the `target/` directory.

## Manual Profile Selection

You can explicitly specify the platform profile:

### Mac Intel (x86_64)
```bash
mvn clean package -P mac
```
Output: `target/usage-tracker-mac.jar`

### Mac Apple Silicon (M1/M2/M3)
```bash
mvn clean package -P mac-aarch64
```
Output: `target/usage-tracker-mac-aarch64.jar`

### Windows
```bash
mvn clean package -P windows
```
Output: `target/usage-tracker-windows.jar`

### Linux
```bash
mvn clean package -P linux
```
Output: `target/usage-tracker-linux.jar`

## Running the JAR

After building, run the application with the platform-specific JAR:

```bash
# On Mac Apple Silicon:
java -jar target/usage-tracker-mac-aarch64.jar

# On Mac Intel:
java -jar target/usage-tracker-mac.jar

# On Windows:
java -jar target/usage-tracker-windows.jar

# On Linux:
java -jar target/usage-tracker-linux.jar
```

## Notes

- The shaded JAR includes all dependencies including platform-specific JavaFX native libraries
- Make sure you're using Java 21 or later
- On Mac, you may need to grant accessibility permissions for the floating widget to work properly
- Each platform profile includes the correct JavaFX classifier (win, mac, mac-aarch64, linux)
