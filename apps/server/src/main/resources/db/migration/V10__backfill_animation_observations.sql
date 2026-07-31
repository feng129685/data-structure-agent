INSERT INTO animation_observations (
    animation_record_id,
    user_id,
    observation,
    created_at
)
SELECT
    animation_records.id,
    animation_records.user_id,
    animation_records.observation,
    animation_records.updated_at
FROM animation_records
WHERE animation_records.user_id IS NOT NULL
  AND animation_records.observation IS NOT NULL
  AND TRIM(animation_records.observation) <> ''
  AND NOT EXISTS (
    SELECT 1
    FROM animation_observations
    WHERE animation_observations.animation_record_id = animation_records.id
      AND animation_observations.user_id = animation_records.user_id
      AND animation_observations.observation = animation_records.observation
  );
