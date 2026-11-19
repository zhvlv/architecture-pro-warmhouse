package main

import (
	"context"
	"encoding/json"
	"log"
	"math/rand"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Reading contains a simulated temperature reading
type Reading struct {
	Value       float64   `json:"value"`
	Unit        string    `json:"unit"`
	Timestamp   time.Time `json:"timestamp"`
	Location    string    `json:"location"`
	Status      string    `json:"status"`
	SensorID    string    `json:"sensor_id"`
	SensorType  string    `json:"sensor_type"`
	Description string    `json:"description"`
}

// TemperatureHandler handles requests for simulated temperature readings
type TemperatureHandler struct {
	mu       sync.Mutex
	previous map[string]int
}

// ServeHTTP handles temperature requests and health checks
func (h *TemperatureHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	if path != "/health" && path != "/temperature" && !strings.HasPrefix(path, "/temperature/") {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "Endpoint not found"})
		return
	}

	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "Method not allowed"})
		return
	}

	if path == "/health" {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
		return
	}

	location := strings.TrimSpace(r.URL.Query().Get("location"))
	sensorID := r.URL.Query().Get("sensor_id")
	alias := r.URL.Query().Get("sensorId")
	if sensorID != "" && alias != "" && sensorID != alias {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Conflicting sensor identifiers"})
		return
	}

	if sensorID == "" {
		sensorID = alias
	}

	if strings.HasPrefix(path, "/temperature/") {
		pathID := strings.TrimPrefix(path, "/temperature/")
		if pathID == "" || strings.Contains(pathID, "/") {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "Endpoint not found"})
			return
		}

		if sensorID != "" && sensorID != pathID {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Conflicting sensor identifiers"})
			return
		}

		sensorID = pathID
	}

	if location == "" {
		location = map[string]string{
			"1": "Living Room",
			"2": "Bedroom",
			"3": "Kitchen",
		}[sensorID]
		if location == "" {
			location = "Unknown"
		}
	}

	if sensorID == "" {
		sensorID = map[string]string{
			"Living Room": "1",
			"Bedroom":     "2",
			"Kitchen":     "3",
		}[location]
		if sensorID == "" {
			sensorID = "0"
		}
	}

	// A repeated poll must produce a new simulated value, even after rounding
	key := sensorID + "\x00" + location
	h.mu.Lock()
	cents := rand.Intn(6501)
	if prior, exists := h.previous[key]; exists && prior == cents {
		cents = (cents + 1) % 6501
	}
	h.previous[key] = cents
	h.mu.Unlock()

	writeJSON(w, http.StatusOK, Reading{
		Value:       float64(cents-2000) / 100,
		Unit:        "°C",
		Timestamp:   time.Now().UTC(),
		Location:    location,
		Status:      "active",
		SensorID:    sensorID,
		SensorType:  "temperature",
		Description: "Simulated temperature reading",
	})
}

// writeJSON writes a JSON response with the specified status code
func writeJSON(w http.ResponseWriter, code int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(value)
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8081"
	}

	server := &http.Server{
		Addr: ":" + port,
		Handler: &TemperatureHandler{
			previous: make(map[string]int),
		},
	}
	go func() {
		log.Printf("Temperature simulator listening on %s", server.Addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Printf("Shutdown: %v", err)
	}
}
