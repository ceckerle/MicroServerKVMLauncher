# MicroServerKVMLauncher
A wrapper to launch the KVM Viewer for the HP MicroServer N36L/N40L/N54L Remote Access Card on modern Java Runtimes.

Requires a Java 8 or newer Runtime. Successfully tested on Mac OS X 11 and Windows 10 with Java 8 and 15.

## Usage
java -jar MicroServerKVMLauncher.jar &lt;host> &lt;user> &lt;password>

e.g. java -jar MicroServerKVMLauncher.jar 192.168.1.17 admin password

## Limitations
- TLS certificate validation and strong protocols/cipher suites are disabled. Use only in a secure network environment.

## Possible enhancements
- Support launching the virtual media application
- Support other platforms (e.g. Dell iDRAC6 seems to be using the same KVM Viewer)
