
# VPN management service

This application allows you to manage OpenVPN profiles. The main goal was to create very simple solution without any dependencies.

The app contains HTTP server inside that allows to make CRUD operations with profiles. 

The app does not use any libraries - only Java core (18+) and all code is stored in one file. 

You can change code on server as script file and run without compilation. 

If you prefer to avoid java installation you can compile the code with GraalVM into native executable. 

## Usage

- Run as: `java VPNMan.java <parameters>`
  - run with `--help` to show help message
  - run with `--api` to show API in OpenAPI v3 format
  - run with `--default-template` to show default client template

### Parameters

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


## Web interface

All web interface static files should be located in `--static` dir.

## Project specific

1. Project does not use any external libraries - only Java core
2. Project contains implementation of standard functions like
- Check directory emptiness
- Object to JSON serialization
- JSON to Object deserialization
- MIME types for serving files detection
- etc
3. All this implementations are simplified and limited to current project needs:
- JSON->Object implementation does not support nested objects with more than 1 level depth. Array on zero-level is not supported as well
  - App can parse JSON like `{"name": "John Doe", "age": 30, "hobbies": ["Reading", "Running"], "address": {"city": "New York", "country": "USA"}}`
  - But not JSON like `[1,2,3,4]`
  - And not like `{"obj":{"test":{"val":1}}}` because it has depth more than 1
  - Exponent in number format is not also supported
  - etc
- No logging
- No backups
- All output just threw into System.out

But it's enough to deal with its main goal: managing VPN profiles.

