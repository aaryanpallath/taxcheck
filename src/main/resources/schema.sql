-- One row per location and period with an unchanged rate. Consecutive quarters with the same rate are merged, so a
-- row spans from the quarter the rate took effect to the last day it applied.
CREATE TABLE IF NOT EXISTS rate_period (
  location_code  CHAR(4)       NOT NULL,
  location_name  VARCHAR(80)   NOT NULL,
  state_rate     DECIMAL(7, 5) NOT NULL,
  local_rate     DECIMAL(7, 5) NOT NULL,
  rta_rate       DECIMAL(7, 5) NOT NULL,
  combined_rate  DECIMAL(7, 5) NOT NULL,
  effective_from DATE          NOT NULL,
  effective_to   DATE          NOT NULL,
  PRIMARY KEY (location_code, effective_from),
  CONSTRAINT valid_period CHECK (effective_from <= effective_to),
  CONSTRAINT parts_add_up CHECK (combined_rate = state_rate + local_rate + rta_rate)
);
CREATE INDEX IF NOT EXISTS rate_period_name ON rate_period (location_name);
