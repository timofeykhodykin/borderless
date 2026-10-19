package creds

import (
	"math/rand"
	"strings"
)

// Name data for join requests. The lists are stored encoded and decoded once at start-up; they are display data
// only — never identifiers, never logged.
var (
	maleNamesData   = "++GdJcNyirL68J0uw3qKvPrxR06DF+HYnqH3T5IX79iSe50Ow3qKvPrxnSvDflDYu6HwT5EX5NiWe50OwkeLifvEnSPDf4qxIaHdT5MW2NmooM2Uw1eLiPrznA/De1DYuqHzTqAX7tibofCUw1WKuPvKnSvCR4qw+8hHToEX6tmqofVOqBfi2JJ7nQzDf4qy+vOdIMJHUNi5ofZOoxfu2JOgzE6oF+rYmXudDcN8ir37wEdOgBba2JOh/k6tFtrYk6H0lMNTirj7zJ0mw3+KsyGh2U6mF+fYk6DMlMNTirT7yZwcwkeKsPvIR06GF+jYmKH4Tq4X4tiSe50Lw3SKtvrxR06LF+jYm6HwlMNfirv7z5wewktQ2LOh9k+fFtUC++udJsJHirD7yp0lGRfB2J6h806uF+LYn3udAsN3irL68J0mw3tQ2Leh/U+TF+AC++2dLsJFirr7xJ0nGRfG2JOgyE6jF+LYkHudA8N/irL7yZwcw3dQ2Lah9U6pF+TYkKH9TqrNipb7yp0rw3RQ2LSh/U6hF+/YkHudAcJWi4r68UdOsxfk2Jeh/U6uzYqo+vKcH8N8irj7zEdOshfv2auh/k6mF+MC+9CcHMN3irX7yZwfw3yKuPvDR06xF+LYl6HzT5cX79iSe506wlaKvPvPnB4="
	femaleNamesData = "++GdJcN/irX7wUdOgxfh2bqh8E6jzYqY+8ydLsJGi4r7wZwfw3+LhyGh3U6uF+nYnqH2TqsX59ibe50Ow3qKtfvBR06BF+/Zq6H9lMNVir368Z0gw3qKsPvLnS4ZF8jYk6H3T5EX5NmrofVPnM2KnPvBnB7CS4uHIaHYTqEX6gL75J0kw3eLivvEnB7Df4q1+8FHToYX4dieofBOo82KnfvKnSbDcIq4+8OdK8JFirghodVPkxfi2Jah/ZTDXYqw+vGdLhkXwNmrofVPkhbY2JOh8E6jzYqS+vCdK8N6irD6/kdOiBbU2Jqh806hFtYC++2dLsJHirv7wZwew3+LivvBR06PF+rZq6H1Tq4X6gL77Z0uwkeKsPr+R06PF+LYkKH9Tq4X6gL77J0uw3OKvfvHnSrDd1DYtqH9T5EX6tiQoMFPnM2KlvvKnBLDdIq4IaHSTq0X4diTofBOo82KqfvDnSvCRYqz+8GdI8N3UNiKofNPlxfi2aR7nTzDd4uK+v2cEcN6irghoeNOqBfi2aR7nTHDeoq4"
	lastNamesData   = "++GdJcNyirL68J0rw3KKuiGh3U6uF+7Zq6H4TqYX6AL74Z0jwkWKtvvMnSDDdVDYuqH9T5MX6tiWofNOoc2KmfvEnSXDeYq6IaHcTqYX4dmgofSUw1aKvfvKnBLCRoqy+8mdJxkXy9ieofZPnBfv2Jl7nQ/DeYuI+8mcH8N5irohod9Ooxbb2JOh9k+fF+/YmXudDMNyirP7yZ0kw3+KsSGh306tF+HYkaHzTqHNipr7z5wew3mKufr9nA/DdVDYuKDNTqsX6diVoM1Pnxfv2Jl7nQrDd4q6+vqdKsN5irohodhOoBfk2auh806hzYqe+vKdJMN5irohodpOoxfj2a2h+E6hzYqf+8GcG8N3i4j7z50sGRfC2Jmh/U6uF+TYmXudBMN3irP7yZ0jw3+KtSGh106tF+jYm6H2T4IX6AL7650gw3CKs/vPnSwZF8DYlaHxTqMW2tiVof+Uw12LiPvBnSLCRoqy+8+dJxkXwNmoofpOrhfv2a2h806hzYqS+vKdKcJLirT7yZ0jGRfB2J6h/E6mF+7YnqH/lMNbirj7y50uwkeKtvvDR06PF+/Yn6H/TqYX7tieof+Uw1uKsPr0nS7Dfoqz+8+dLBkXxtiVoM1OrRft2JWh/5TDWoqw+8udJsJFirD7zEdOjhfi2JGh806oF+rYnqH/lMNairb7w50mw32KtvvDR06NFtrYkKHzTqHNipb68JwcwkeKtvvDnB/DfYqw+8hHTowX6tiZofZOrRfoAvvunSvCRYuI+8+dLBkXxdiVofdPkxfk2JmgzE6pF+LYknudAcN5irf7z50sGRf62Juh+E6hFtvYkaH1TqrNiqj7z50iw3eKtfvPnSwZF/vYnqHxT4IX59iVof+Uw2aKvfrxnS3Dcoq9+8NHTrIX5tiToM1Orhfk2Jl7nT/DeYqy+8+dJcN5irohoexOrRfh2JWh/0+fFsvYmXudP8JFir37zp0uw3qKtvvDR06xF+rZq6H9T5IX5NiZe508w3+LivvPnSwZF/jYlaH2T5IW2NiVofSUw2WLiPrynS/DcouO+8udIMN+UNiPofVOqBfi2JSh8k6tF+gC+9WcHsN5irP7z50sGRf+2bqh+U6tFtrYlaH/lMNgirj7yJ0kw3mKuvrwnSTDf4qxIaHqTqYW2tiWoMZOqs2Kp/vLnSDDdYqz+8SdLA=="

	maleFirstNames   = decodeList(maleNamesData)
	femaleFirstNames = decodeList(femaleNamesData)
	lastNames        = decodeList(lastNamesData)
)

func femaleSurname(s string) string {
	r := []rune(s)
	suffix := func(p string) bool { return strings.HasSuffix(s, p) }
	switch {
	case suffix("ий"), suffix("ый"), suffix("ой"):
		return string(r[:len(r)-2]) + "ая"
	case suffix("ов"), suffix("ев"), suffix("ин"), suffix("ын"), suffix("ёв"):
		return s + "а"
	}
	return s
}

// pickName returns a name for a join request; sometimes only a first name.
func pickName() string {
	if len(maleFirstNames) == 0 || len(femaleFirstNames) == 0 || len(lastNames) == 0 {
		return "Alex"
	}
	isFemale := rand.Intn(2) == 0
	first := maleFirstNames[rand.Intn(len(maleFirstNames))]
	if isFemale {
		first = femaleFirstNames[rand.Intn(len(femaleFirstNames))]
	}
	if rand.Float32() < 0.3 {
		return first
	}
	last := lastNames[rand.Intn(len(lastNames))]
	if isFemale {
		last = femaleSurname(last)
	}
	return first + " " + last
}
