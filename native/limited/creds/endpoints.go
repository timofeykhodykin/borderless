package creds

// Service endpoints and client credentials. The values are stored encoded and decoded at start-up; the API
// versions are plain numbers.
var (
	apiHostData       = "SgEksGWsdGVO"
	clientIDData      = "E0F0rST0ag=="
	cdnKeyData        = "aDYA01aNFk9vOAXcUoUbSmo="
	cdnURLData        = "QwU57mD9dSdIECHyYOk1Y0gVI7BhsnVuSV8p8Q=="
	loginURLData      = "QwU57mD9dSdHHir3feksYwUDOLEspjl8FhYo6kymNGdFCCDBZ6gxbUU="
	apiBaseData       = "QwU57mD9dSdKASSwZax0el5eIPtnrzVsBA=="
	webOriginData     = "QwU57mD9dSddGmPsZg=="
	joinBaseData      = "QwU57mD9dSddGmP9fKp1a0odIbF5qDNmBA=="
	clientAIDData     = "HUN1qSf/bQ=="
	clientASecretData = "ZgQM5lWmEUxyNQLXUL0dZm4+Je4="
	clientBIDData     = "E0N9rCX3bA=="
	clientBSecretData = "Rzwf7UeuF0tSISPuJrE8Z0cVIPA="

	callsAPIHost   = decode(apiHostData)
	callsClientID  = decode(clientIDData)
	callsCDNKey    = decode(cdnKeyData)
	callsCDNURL    = decode(cdnURLData)
	callsLoginURL  = decode(loginURLData)
	callsAPIBase   = decode(apiBaseData)
	callsWebOrigin = decode(webOriginData)
	callsJoinBase  = decode(joinBaseData)
)

const (
	// apiVersion is the API version of the alternate path.
	apiVersion = "5.275"
	// callsAPIVersion is the API version of the preferred path.
	callsAPIVersion = "5.276"

	// minFetchInterval is the base gap between requests; a random fraction of a second is added on top.
	minFetchInterval = 3
)

// appCreds is one client credential pair of the alternate path.
type appCreds struct {
	ClientID     string
	ClientSecret string
}

// alternateCreds are tried in order, rotating on failure.
var alternateCreds = []appCreds{
	{ClientID: decode(clientAIDData), ClientSecret: decode(clientASecretData)},
	{ClientID: decode(clientBIDData), ClientSecret: decode(clientBSecretData)},
}
