ALTER TABLE reference_pairs
    ADD COLUMN IF NOT EXISTS from_poi_name VARCHAR(128) NULL AFTER from_poi_id,
    ADD COLUMN IF NOT EXISTS to_poi_name VARCHAR(128) NULL AFTER to_poi_id;

UPDATE reference_pairs rp
JOIN attractions af ON af.poi_id COLLATE utf8mb4_0900_ai_ci = rp.from_poi_id COLLATE utf8mb4_0900_ai_ci
SET rp.from_poi_name = af.name
WHERE rp.from_poi_name IS NULL OR rp.from_poi_name = '';

UPDATE reference_pairs rp
JOIN attractions at2 ON at2.poi_id COLLATE utf8mb4_0900_ai_ci = rp.to_poi_id COLLATE utf8mb4_0900_ai_ci
SET rp.to_poi_name = at2.name
WHERE rp.to_poi_name IS NULL OR rp.to_poi_name = '';
