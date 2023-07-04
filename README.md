
# VPN management service

VPN Management Service is a lightweight solution for managing OpenVPN profiles. It's designed with simplicity in mind, aiming to provide a user-friendly way to perform CRUD operations on profiles without the need for external dependencies.

This Java-based application (version 18 or higher) features a built-in HTTP server and keeps all its code in a single file for easier maintenance. Want to update the server-side code? Simply edit the script file and run it without compiling!

For those who'd rather not install Java, the code can be compiled into a native executable using [GraalVM](https://www.graalvm.org/).

## Getting Started

1. Install OpenVPN
- `easyrsa` must be installed and [used](https://community.openvpn.net/openvpn/wiki/EasyRSA3-OpenVPN-Howto) for key/cert management
- `vars` with default profile parameters must be specified
2. Install and run VPNMan - follow the [installation guide](#installation)
3. Go to specified during the installation http://server:port/
4. If you are using the default UI you will see:

- Welcome page
  ![image](https://github.com/tar/vpnman/assets/658089/ef7f64cf-d657-4c4f-bb1e-2cff0a4f61ce)
- Invalid secret
  ![image](https://github.com/tar/vpnman/assets/658089/a7b461a7-a880-4dce-819e-54f43004a352)
- Profiles page
  ![image](https://github.com/tar/vpnman/assets/658089/5d88375c-8fb3-4329-9439-45e1da6483cf)

5. Actions
- Type your secret and press `Go`
- Retry if secret is not correct
- Type new profile name and press `Create new`
- Press `Update all` to recreate all non-revoked profiles (should be performed if some profiles were already issued\revoked before VPNMan)
- For each row
  - `Download` - the direct link to the profile. Do not require secret to proceed
  - `Revoke` - revoke certificates and delete profile. Couldn't be undone.
6. Secret
- This service does not provide any authentication and the only security gate to profile management is secret
- Secret is the string, that become part of profiles API URL
- You can specify your own secret as a parameter or use random UUID
- Secret will be printed to the output during the startup
- Keep it safe and do not forget to change it periodically

## Installation

You can launch the application with the following command:

```bash
java VPNMan.java <parameters>
```

### General parameters

This type of parameters does not require value.

|         Parameter           | Description                                                                                           |
| --------------------------- |-------------------------------------------------------------------------------------------------------|
| --help | Prints help message. All other parameters will be ignored                                             |
| --api | Prints HTTP API in OpenAPI v3 format                                                                  |
| --default-template| Prints default client profile tempate. You can specify your own template with `--template` parameter  |
| --dry-run | Development mode: some checks will be ignored. No actual `easyrsa` call will be performed|


### Configuration

**VPN management parameters:**

|         Parameter           | Optional |                            Description                                  |
| --------------------------- | -------- | ------------------------------------------------------------------------|
| --easyrsa=<easyrsa_dir>     |   false  | Path to EasyRSA installation dir                                        |
| --output=<output_dir>       |   true   | Path to dir for client profiles. `<easyrsa>/client_profiles` is default |
| --template=<template_path>  |   true   | Path to client profile template                                         |
| --vpnurl=<vpnserver_url>    |   true   | IP/DN to VPN server. Required if `--template` is not specified          |
| --vpnport=<vpnserver_port>  |   true   | VPN server port. Required if `--template` is not specified              |
| --ca=<ca.crt path>          |   true   | VPN server ca.crt file. Required if `--template` is not specified       |
| --tlsauth=<.tlsauth path>   |   true   | VPN server tlsauth file. Required if `--template` is not specified      |

You should specify path to template file or set parameters for default one. Template should contain:
- `${KEY}` - placeholder for client key (`<key>${KEY}</key>`)
- `${CERT}` - placeholder for client cert (`<cert>${CERT}</cert>`)

If you don't have your own template you can skip `--template` parameter but in this case you must specify:
- `vpnurl`
- `vpnport`
- `ca`
- `tlsauth`


**Server parameters:**

|         Parameter           | Optional |                            Description                                  |
| --------------------------- | -------- | ------------------------------------------------------------------------|
| --url                       |   true   | IP/DN on which server will be started. 127.0.0.1 is default             |
| --port                      |   true   | Port for service API. 8666 is default                                   |
| --context                   |   true   | Secret context for managing profiles API. Random UUID if not specified  |
| --static                    |   true   | Path to dir with static content with UI. webapp is default              |

App does not provide any authorization. Profile management secured by setting '--context' parameter. This secret will be used in '/${secret}/profiles' path.

App allows direct VPN client profile download by direct link without sharing a secret. So you can share this link to profile owner.

If static dir will be empty after start, app will automatically download default UI from this repository.

### Systemd service

To manage service start/stop/restart behaviour it's easier to create system.d service. Here is an example:
```bash
[Unit]
Description=VPN Manager
After=network.target

[Service]
ExecStart=/usr/bin/java /opt/vpnman/VPNMan.java --config=/etc/default/vpnman.properties
User=root
Environment="JAVA_OPTS=-Xmx128m"
Restart=on-failure
SuccessExitStatus=0

[Install]
WantedBy=multi-user.target
```

## How it works

### Create profile

1. Service calls `easyrsa` binary to generate client key and cert with specified name
```bash
./easyrsa build-client-full ${name} nopass
```
2. Service replaces placeholders `${KEY}` and `${CERT}` in client profile template
- Key from `${easyRSADir}/pki/private/${name}.key`
- Cert from `${easyRSADir}/pki/issued/${name}.crt`
3. Service calculates hash from client profile content
4. Service stores client profile in output directory with name `${hash}_-_${name}.ovpn`

### Revoke profile

1. Service calls `easyrsa` with profile name to revoke keys
```bash
./easyrsa revoke ${name}
```
2. Service removes client profile template from output directory

### Update all profiles

1. Service removes all client profiles from output directory
2. Service parses `index.txt` provided by `easyrsa` and recreates client profiles from non-revoked certs

### Web interface

1. All web interface static files should be placed in the directory specified by the `--static` parameter.
2. If no such parameter will be specified or specified directory will be empty VPNMan will download default UI from GitHub.
3. Web interface is built using vanilla JS and [mincss](https://mincss.com/)

## Considerations

Please note that while this project does not use external libraries and provides a number of standard functions such as directory emptiness check, object-to-JSON serialization, JSON-to-object deserialization, MIME type detection for serving files, etc., these implementations are simplified and limited to the project's needs.

For example, the JSON-to-object implementation doesn't support nested objects with depth more than 1, and zero-level arrays aren't supported. The implementation is suitable for parsing JSON structures such as
```json
{"name": "John Doe", "age": 30, "hobbies": ["Reading", "Running"], "address": {"city": "New York", "country": "USA"}}
```
But it won't work for arrays like `[1,2,3,4]` or nested objects like
```json
{"obj":{"test":{"val":1}}}
```

Also, keep in mind that the project doesn't currently support logging or backups, and all output is directed to System.out.

## Contribute

Feel free to dive in!