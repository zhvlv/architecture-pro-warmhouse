package services

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// UpstreamError represents a failed request to a required service
type UpstreamError struct {
	Status  int
	Message string
}

// Error returns the upstream error message
func (e *UpstreamError) Error() string {
	return e.Message
}

// MicroserviceClient handles requests to the device and telemetry services
type MicroserviceClient struct {
	DeviceURL    string
	TelemetryURL string
	HTTPClient   *http.Client
}

// NewMicroserviceClient creates a new microservice client
func NewMicroserviceClient(deviceURL, telemetryURL string) *MicroserviceClient {
	return &MicroserviceClient{
		DeviceURL:    strings.TrimRight(deviceURL, "/"),
		TelemetryURL: strings.TrimRight(telemetryURL, "/"),
		HTTPClient: &http.Client{
			Timeout: 3 * time.Second,
		},
	}
}

// Call sends a JSON request to a required service
func (s *MicroserviceClient) Call(ctx context.Context, method, base, path string, input, output any) error {
	var body io.Reader
	if input != nil {
		data, err := json.Marshal(input)
		if err != nil {
			return &UpstreamError{http.StatusBadRequest, "Invalid JSON payload"}
		}

		body = bytes.NewReader(data)
	}

	request, err := http.NewRequestWithContext(ctx, method, base+path, body)
	if err != nil {
		return &UpstreamError{http.StatusServiceUnavailable, "Invalid upstream configuration"}
	}

	if input != nil {
		request.Header.Set("Content-Type", "application/json")
	}

	response, err := s.HTTPClient.Do(request)
	if err != nil {
		return &UpstreamError{http.StatusServiceUnavailable, "Required service is unavailable"}
	}
	defer response.Body.Close()

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		status := response.StatusCode
		message := "Required service is unavailable"
		if status >= 400 && status < 500 {
			var upstream struct {
				Error string `json:"error"`
			}

			if json.NewDecoder(response.Body).Decode(&upstream) == nil && upstream.Error != "" {
				message = upstream.Error
			} else {
				message = fmt.Sprintf("Upstream request failed with HTTP %d", status)
			}
		} else {
			status = http.StatusServiceUnavailable
		}

		return &UpstreamError{status, message}
	}

	if output != nil {
		if err := json.NewDecoder(response.Body).Decode(output); err != nil {
			return &UpstreamError{http.StatusServiceUnavailable, "Required service returned an invalid response"}
		}
	}

	return nil
}

// Health checks the required device and telemetry services
func (s *MicroserviceClient) Health(ctx context.Context) error {
	if err := s.Call(ctx, http.MethodGet, s.DeviceURL, "/health", nil, nil); err != nil {
		return err
	}

	return s.Call(ctx, http.MethodGet, s.TelemetryURL, "/health", nil, nil)
}
