# IRS Form 8582 Processor

Spring Boot application for processing IRS Form 8582 - Passive Activity Loss Limitations.

## Features

- Calculate passive activity losses based on IRS Form 8582 rules
- Special allowance calculation for rental real estate activities
- Modified AGI phase-out calculations
- Active participation tracking
- Prior year unallowed loss carryforward

## Technology Stack

- Java 17
- Spring Boot 3.2.0
- Spring Data JPA
- PostgreSQL
- Lombok

## API Endpoints

### Create Passive Activity
```
POST /api/form8582/activities
```

### Get Activities by Year
```
GET /api/form8582/activities/{year}
```

### Calculate Form 8582
```
POST /api/form8582/calculate
```

## Database Setup

1. Create PostgreSQL database: `form8582db`
2. Update `application.properties` with your database credentials

## Running the Application

```bash
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

## Key Calculations

- **Special Allowance**: $25,000 maximum for rental real estate with active participation
- **AGI Phase-out**: Begins at $100,000, complete at $150,000
- **Loss Allocation**: Proportional based on activity losses

## Based on IRS Form 8582 Instructions (2024)