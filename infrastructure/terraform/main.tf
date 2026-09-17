locals {
  project_name = "cloudscale"
  environment  = "dev"

  name_prefix = "${local.project_name}-${local.environment}"
}