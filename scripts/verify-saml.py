import datetime
import json
import sys
import uuid
import xml.etree.ElementTree as ET


SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol"
ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion"
ECP = "urn:oasis:names:tc:SAML:2.0:profiles:SSO:ecp"


def build_request(endpoint, client_id, acs_url):
    envelope = ET.Element(f"{{{SOAP}}}Envelope")
    body = ET.SubElement(envelope, f"{{{SOAP}}}Body")
    request = ET.SubElement(body, f"{{{PROTOCOL}}}AuthnRequest", {
        "ID": "_" + uuid.uuid4().hex,
        "Version": "2.0",
        "IssueInstant": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "Destination": endpoint,
        "AssertionConsumerServiceURL": acs_url,
        "ProtocolBinding": "urn:oasis:names:tc:SAML:2.0:bindings:PAOS",
    })
    ET.SubElement(request, f"{{{ASSERTION}}}Issuer").text = client_id
    return ET.tostring(envelope, encoding="unicode")


def read_response(response_xml, request_xml):
    request = ET.fromstring(request_xml).find(f"{{{SOAP}}}Body/{{{PROTOCOL}}}AuthnRequest")
    envelope = ET.fromstring(response_xml)
    fault = envelope.find(f"{{{SOAP}}}Body/{{{SOAP}}}Fault")
    if fault is not None:
        raise ValueError("SOAP fault: " + " ".join(fault.itertext()))
    response = envelope.find(f"{{{SOAP}}}Body/{{{PROTOCOL}}}Response")
    if response is None:
        raise ValueError("No SAML Response found. Check client protocol and saml.allow.ecp.flow.")
    status = response.find(f"{{{PROTOCOL}}}Status/{{{PROTOCOL}}}StatusCode")
    if status is None or status.get("Value") != "urn:oasis:names:tc:SAML:2.0:status:Success":
        raise ValueError("SAML login failed. Check credentials, required actions and backend access. "
                         + ET.tostring(response, encoding="unicode"))
    if response.get("InResponseTo") != request.get("ID"):
        raise ValueError("SAML response does not match the authentication request.")
    acs_url = request.get("AssertionConsumerServiceURL")
    if response.get("Destination") != acs_url:
        raise ValueError("Unexpected SAML response destination.")
    ecp_response = envelope.find(f"{{{SOAP}}}Header/{{{ECP}}}Response")
    if ecp_response is None or ecp_response.get("AssertionConsumerServiceURL") != acs_url:
        raise ValueError("Unexpected ECP assertion consumer URL.")
    assertion = response.find(f"{{{ASSERTION}}}Assertion")
    if assertion is None:
        raise ValueError("No readable SAML assertion found (encrypted assertions are not supported).")
    client_id = request.findtext(f"{{{ASSERTION}}}Issuer")
    audiences = assertion.findall(f"{{{ASSERTION}}}Conditions/{{{ASSERTION}}}AudienceRestriction/{{{ASSERTION}}}Audience")
    if not any(audience.text == client_id for audience in audiences):
        raise ValueError("SAML assertion is not intended for the requested client.")
    attributes = {}
    for attribute in assertion.findall(f"{{{ASSERTION}}}AttributeStatement/{{{ASSERTION}}}Attribute"):
        values = ["".join(value.itertext()) for value in attribute.findall(f"{{{ASSERTION}}}AttributeValue")]
        attributes.setdefault(attribute.get("Name"), []).extend(values)
    if not attributes:
        raise ValueError("SAML assertion contains no mapped attributes.")
    return {
        "nameId": assertion.findtext(f"{{{ASSERTION}}}Subject/{{{ASSERTION}}}NameID"),
        "attributes": attributes,
    }


def main():
    try:
        if sys.argv[1] == "request":
            print(build_request(*sys.argv[2:]))
        elif sys.argv[1] == "response":
            print(json.dumps(read_response(sys.stdin.read(), sys.argv[2]), indent=2))
        else:
            raise ValueError("Expected request or response command.")
    except (ValueError, ET.ParseError) as error:
        sys.exit(f"Error: {error}")


if __name__ == "__main__":
    main()