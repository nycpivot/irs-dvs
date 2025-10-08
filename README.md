# IRS Form 8582 Processor

Spring Boot application for processing IRS Form 8582 - Passive Activity Loss Limitations.

## Features

- Calculate passive activity losses based on IRS Form 8582 rules
- Special allowance calculation for rental real estate activities
- Modified AGI phase-out calculations
- Support for active and material participation rules
- Prior year unallowed loss tracking

## Technology Stack

- Java 17
- Spring Boot 3.2.0
- Spring Data JPA
- PostgreSQL
- Lombok

## Getting Started

### Prerequisites

- JDK 17 or later
- Maven 3.6+
- PostgreSQL 12+

### Database Setup

```sql
CREATE DATABASE form8582db;
```

### Running the Application

```bash
mvn spring-boot:run
```

## API Endpoints

### Calculate Passive Activity Loss

```
POST /api/form8582/calculate
Parameters:
- taxpayerId: String
- taxYear: Integer
- modifiedAgi: BigDecimal
- filingStatus: String (SINGLE, MARRIED_FILING_JOINTLY, MARRIED_FILING_SEPARATELY)
```

## Key Concepts

### Passive Activity
A passive activity is any trade or business activity in which the taxpayer does not materially participate.

### Special Allowance
A special allowance of up to $25,000 ($12,500 if married filing separately) for rental real estate activities with active participation.

### Modified AGI Phase-out
The special allowance is reduced by 50% of the amount by which modified AGI exceeds $100,000 ($50,000 if married filing separately).