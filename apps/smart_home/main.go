package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"smarthome/db"
	"smarthome/handlers"
	"smarthome/services"

	"github.com/gin-gonic/gin"
)

func main() {
	// Select application mode
	mode := getEnv("MIGRATION_MODE", "legacy")

	// Initialize temperature service
	temperatureAPIURL := getEnv("TEMPERATURE_API_URL", "http://temperature-api:8081")
	temperatureService := services.NewTemperatureService(temperatureAPIURL)
	log.Printf("Temperature service initialized with API URL: %s\n", temperatureAPIURL)

	// Initialize router
	router := gin.Default()

	// API routes
	apiRoutes := router.Group("/api/v1")

	switch mode {
	case "legacy":
		// Set up database connection
		dbURL := getEnv("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/smarthome")
		database, err := db.New(dbURL)
		if err != nil {
			log.Fatalf("Unable to connect to database: %v\n", err)
		}
		defer database.Close()

		log.Println("Connected to database successfully")

		// Register sensor routes
		sensorHandler := handlers.NewSensorHandler(database, temperatureService)
		sensorHandler.RegisterRoutes(apiRoutes)

		// Health check endpoint
		router.GET("/health", func(c *gin.Context) {
			ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
			defer cancel()
			if err := database.Pool.Ping(ctx); err != nil {
				c.JSON(http.StatusServiceUnavailable, gin.H{"error": "Database is unavailable"})
				return
			}
			c.JSON(http.StatusOK, gin.H{"status": "ok"})
		})
	case "services":
		// Initialize microservice client
		client := services.NewMicroserviceClient(
			getEnv("DEVICE_API_URL", "http://device-service:8082"),
			getEnv("TELEMETRY_API_URL", "http://telemetry-service:8083"),
		)

		// Register sensor routes
		microserviceHandler := handlers.NewMicroserviceHandler(client, temperatureService)
		microserviceHandler.RegisterRoutes(apiRoutes)

		// Health check endpoint
		router.GET("/health", func(c *gin.Context) {
			ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
			defer cancel()
			if err := client.Health(ctx); err != nil {
				c.JSON(http.StatusServiceUnavailable, gin.H{"error": "Required service is unavailable"})
				return
			}
			c.JSON(http.StatusOK, gin.H{"status": "ok"})
		})
	default:
		log.Fatalf("Unknown MIGRATION_MODE %q; expected legacy or services", mode)
	}

	// Start server
	srv := &http.Server{
		Addr:    getEnv("PORT", ":8080"),
		Handler: router,
	}

	// Start the server in a goroutine
	go func() {
		log.Printf("Server starting on %s\n", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v\n", err)
		}
	}()

	// Wait for interrupt signal to gracefully shut down the server
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("Shutting down server...")

	// Create a deadline for server shutdown
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("Server forced to shutdown: %v\n", err)
	}

	log.Println("Server exited properly")
}

// getEnv gets an environment variable or returns a default value
func getEnv(key, defaultValue string) string {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}
	return value
}
