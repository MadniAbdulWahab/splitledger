resource "aws_ecr_repository" "api" {
  name = "${local.name}-api"

  # A tag (the git commit SHA) always points to the same image, so a deployment is reproducible.
  image_tag_mutability = "IMMUTABLE"
  # Lets `terraform destroy` remove the repository even when it still contains images.
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Keeps storage under the free 500 MB: only the ten newest images survive.
resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the ten newest images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = {
        type = "expire"
      }
    }]
  })
}
