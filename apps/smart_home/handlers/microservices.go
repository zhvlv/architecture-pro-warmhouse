package handlers

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"time"

	"smarthome/models"
	"smarthome/services"

	"github.com/gin-gonic/gin"
)

// Reading represents a telemetry reading.
type Reading struct {
	Value     float64   `json:"value"`
	Unit      string    `json:"unit"`
	Status    string    `json:"status"`
	Timestamp time.Time `json:"timestamp"`
}

// MicroserviceHandler handles sensor requests through the microservices.
type MicroserviceHandler struct {
	Client             *services.MicroserviceClient
	TemperatureService *services.TemperatureService
}

// NewMicroserviceHandler creates a new MicroserviceHandler
func NewMicroserviceHandler(client *services.MicroserviceClient, temperatureService *services.TemperatureService) *MicroserviceHandler {
	return &MicroserviceHandler{
		Client:             client,
		TemperatureService: temperatureService,
	}
}

// RegisterRoutes registers the sensor and command routes
func (h *MicroserviceHandler) RegisterRoutes(router *gin.RouterGroup) {
	sensors := router.Group("/sensors")
	{
		sensors.GET("", h.GetSensors)
		sensors.GET("/:id", h.GetSensor)
		sensors.POST("", h.CreateSensor)
		sensors.PUT("/:id", h.UpdateSensor)
		sensors.DELETE("/:id", h.DeleteSensor)
		sensors.PATCH("/:id/value", h.UpdateValue)
		sensors.POST("/:id/commands", h.CreateCommand)
		sensors.GET("/temperature/:location", h.GetTemperatureByLocation)
	}

	router.GET("/commands/:id", h.GetCommand)
}

// upstreamError maps microservice errors to HTTP responses.
func upstreamError(c *gin.Context, err error) {
	var upstream *services.UpstreamError
	if errors.As(err, &upstream) {
		c.JSON(upstream.Status, gin.H{"error": upstream.Message})
		return
	}

	c.JSON(http.StatusServiceUnavailable, gin.H{"error": "Required service is unavailable"})
}

// decodeObject reads a JSON object from the request.
func decodeObject(c *gin.Context) (map[string]json.RawMessage, bool) {
	var input map[string]json.RawMessage
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return nil, false
	}

	if input == nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "A JSON object is required"})
		return nil, false
	}

	return input, true
}

// device gets sensor metadata from the device service.
func (h *MicroserviceHandler) device(c *gin.Context, id int) (*models.Sensor, bool) {
	var sensor models.Sensor
	if err := h.Client.Call(c.Request.Context(), http.MethodGet, h.Client.DeviceURL, fmt.Sprintf("/api/v1/devices/%d", id), nil, &sensor); err != nil {
		upstreamError(c, err)
		return nil, false
	}

	return &sensor, true
}

// mergeReading adds a telemetry reading to a sensor response.
func mergeReading(sensor *models.Sensor, reading *Reading) {
	sensor.Value = reading.Value
	sensor.Status = reading.Status
	sensor.LastUpdated = reading.Timestamp
	if reading.Unit != "" {
		sensor.Unit = reading.Unit
	}
}

// enrich adds the latest reading to a sensor response.
func (h *MicroserviceHandler) enrich(c *gin.Context, sensor *models.Sensor) bool {
	path := fmt.Sprintf("/api/v1/devices/%d/readings/latest?refresh=%t", sensor.ID, sensor.Type == models.Temperature)
	var reading Reading
	if err := h.Client.Call(c.Request.Context(), http.MethodGet, h.Client.TelemetryURL, path, nil, &reading); err != nil {
		var upstream *services.UpstreamError

		// A new non-temperature device may legitimately have no numeric reading yet.
		if sensor.Type != models.Temperature && errors.As(err, &upstream) && upstream.Status == http.StatusNotFound {
			return true
		}
		upstreamError(c, err)
		return false
	}

	mergeReading(sensor, &reading)
	return true
}

// GetSensors handles GET /api/v1/sensors
func (h *MicroserviceHandler) GetSensors(c *gin.Context) {
	var sensors []models.Sensor
	if err := h.Client.Call(c.Request.Context(), http.MethodGet, h.Client.DeviceURL, "/api/v1/devices", nil, &sensors); err != nil {
		upstreamError(c, err)
		return
	}

	if sensors == nil {
		sensors = []models.Sensor{}
	}

	for i := range sensors {
		if !h.enrich(c, &sensors[i]) {
			return
		}
	}

	c.JSON(http.StatusOK, sensors)
}

// GetSensor handles GET /api/v1/sensors/:id
func (h *MicroserviceHandler) GetSensor(c *gin.Context) {
	id, ok := positiveID(c)
	if !ok {
		return
	}
	sensor, ok := h.device(c, id)
	if !ok {
		return
	}

	if !h.enrich(c, sensor) {
		return
	}

	c.JSON(http.StatusOK, sensor)
}

// CreateSensor handles POST /api/v1/sensors
func (h *MicroserviceHandler) CreateSensor(c *gin.Context) {
	input, ok := decodeObject(c)
	if !ok {
		return
	}

	var sensor models.Sensor
	if err := h.Client.Call(c.Request.Context(), http.MethodPost, h.Client.DeviceURL, "/api/v1/devices", input, &sensor); err != nil {
		upstreamError(c, err)
		return
	}

	// Registration is complete even if telemetry has not yet arrived. Acquisition is a read operation.
	c.JSON(http.StatusCreated, sensor)
}

// UpdateSensor handles PUT /api/v1/sensors/:id
func (h *MicroserviceHandler) UpdateSensor(c *gin.Context) {
	id, ok := positiveID(c)
	if !ok {
		return
	}
	input, ok := decodeObject(c)
	if !ok {
		return
	}

	if len(input) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "At least one field is required"})
		return
	}

	var value *float64
	if raw, exists := input["value"]; exists {
		if err := json.Unmarshal(raw, &value); err != nil || value == nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "value must be a number"})
			return
		}
		delete(input, "value")
	}

	var sensor *models.Sensor
	if len(input) == 0 {
		sensor, ok = h.device(c, id)
		if !ok {
			return
		}
	} else {
		sensor = &models.Sensor{}
		if err := h.Client.Call(c.Request.Context(), http.MethodPut, h.Client.DeviceURL, fmt.Sprintf("/api/v1/devices/%d", id), input, sensor); err != nil {
			upstreamError(c, err)
			return
		}
	}

	if value != nil {
		reading, ok := h.writeReading(c, sensor, *value, sensor.Status)
		if !ok {
			return
		}
		mergeReading(sensor, reading)
	} else if !h.enrich(c, sensor) {
		return
	}

	c.JSON(http.StatusOK, sensor)
}

// writeReading stores a sensor reading in the telemetry service.
func (h *MicroserviceHandler) writeReading(c *gin.Context, sensor *models.Sensor, value float64, status string) (*Reading, bool) {
	input := gin.H{
		"device_id": sensor.ID,
		"value":     value,
		"unit":      sensor.Unit,
		"status":    status,
	}

	var reading Reading
	if err := h.Client.Call(c.Request.Context(), http.MethodPost, h.Client.TelemetryURL, "/api/v1/readings", input, &reading); err != nil {
		upstreamError(c, err)
		return nil, false
	}

	return &reading, true
}

// UpdateValue handles PATCH /api/v1/sensors/:id/value
func (h *MicroserviceHandler) UpdateValue(c *gin.Context) {
	id, ok := positiveID(c)
	if !ok {
		return
	}

	var input ValueUpdate
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	sensor, ok := h.device(c, id)
	if !ok {
		return
	}

	if _, ok := h.writeReading(c, sensor, *input.Value, input.Status); !ok {
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Sensor value updated successfully"})
}

// DeleteSensor handles DELETE /api/v1/sensors/:id
func (h *MicroserviceHandler) DeleteSensor(c *gin.Context) {
	id, ok := positiveID(c)
	if !ok {
		return
	}

	if err := h.Client.Call(c.Request.Context(), http.MethodDelete, h.Client.DeviceURL, fmt.Sprintf("/api/v1/devices/%d", id), nil, nil); err != nil {
		upstreamError(c, err)
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Sensor deleted successfully"})
}

// CreateCommand handles POST /api/v1/sensors/:id/commands
func (h *MicroserviceHandler) CreateCommand(c *gin.Context) {
	id, ok := positiveID(c)
	if !ok {
		return
	}
	input, ok := decodeObject(c)
	if !ok {
		return
	}

	var result json.RawMessage
	if err := h.Client.Call(c.Request.Context(), http.MethodPost, h.Client.DeviceURL, fmt.Sprintf("/api/v1/devices/%d/commands", id), input, &result); err != nil {
		upstreamError(c, err)
		return
	}

	c.JSON(http.StatusCreated, result)
}

// GetCommand handles GET /api/v1/commands/:id
func (h *MicroserviceHandler) GetCommand(c *gin.Context) {
	id := c.Param("id")

	var result json.RawMessage
	if err := h.Client.Call(c.Request.Context(), http.MethodGet, h.Client.DeviceURL, "/api/v1/commands/"+url.PathEscape(id), nil, &result); err != nil {
		upstreamError(c, err)
		return
	}

	c.JSON(http.StatusOK, result)
}

// GetTemperatureByLocation handles GET /api/v1/sensors/temperature/:location
func (h *MicroserviceHandler) GetTemperatureByLocation(c *gin.Context) {
	NewSensorHandler(nil, h.TemperatureService).GetTemperatureByLocation(c)
}
