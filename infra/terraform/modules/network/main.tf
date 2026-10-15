variable "name" { type = string }
variable "https_egress_cidrs" { type = list(string) }
variable "region" { type = string }
variable "cidr" { type = string }
variable "availability_zones" { type = list(string) }
variable "nat_gateways" { type = number }
resource "aws_vpc" "this" {
  cidr_block           = var.cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = var.name }
}
resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.this.id
  cidr_block              = cidrsubnet(var.cidr, 8, count.index)
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-public-${count.index}" }
}
resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(var.cidr, 8, count.index + 10)
  availability_zone = var.availability_zones[count.index]
  tags              = { Name = "${var.name}-private-${count.index}" }
}
resource "aws_internet_gateway" "this" { vpc_id = aws_vpc.this.id }
resource "aws_route_table" "public" { vpc_id = aws_vpc.this.id }
resource "aws_route" "internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}
resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}
resource "aws_route_table" "private" {
  count  = 2
  vpc_id = aws_vpc.this.id
}
resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}
resource "aws_eip" "nat" {
  count  = var.nat_gateways
  domain = "vpc"
}
resource "aws_nat_gateway" "this" {
  count         = var.nat_gateways
  subnet_id     = aws_subnet.public[count.index].id
  allocation_id = aws_eip.nat[count.index].id
  depends_on    = [aws_internet_gateway.this]
}
resource "aws_route" "private_egress" {
  count                  = var.nat_gateways
  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[count.index].id
}
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = concat([aws_route_table.public.id], aws_route_table.private[*].id)
}
resource "aws_security_group" "ingress" {
  name_prefix = "${var.name}-ingress-"
  description = "Reserved for approved HTTPS ingress; no public ingress rules until compute review"
  vpc_id      = aws_vpc.this.id
}
resource "aws_security_group" "frontend" {
  name_prefix = "${var.name}-frontend-"
  description = "Frontend receives only from approved ingress"
  vpc_id      = aws_vpc.this.id
}
resource "aws_security_group" "api" {
  name_prefix = "${var.name}-api-"
  description = "Internal API receives TLS only from frontend"
  vpc_id      = aws_vpc.this.id
}
resource "aws_security_group" "worker" {
  name_prefix = "${var.name}-worker-"
  description = "Worker has no ingress"
  vpc_id      = aws_vpc.this.id
}
resource "aws_security_group" "migration" {
  name_prefix = "${var.name}-migration-"
  description = "Short-lived migration runner has no ingress"
  vpc_id      = aws_vpc.this.id
}
resource "aws_vpc_security_group_ingress_rule" "frontend" {
  security_group_id            = aws_security_group.frontend.id
  referenced_security_group_id = aws_security_group.ingress.id
  ip_protocol                  = "tcp"
  from_port                    = 8765
  to_port                      = 8765
}
resource "aws_vpc_security_group_egress_rule" "to_frontend" {
  security_group_id            = aws_security_group.ingress.id
  referenced_security_group_id = aws_security_group.frontend.id
  ip_protocol                  = "tcp"
  from_port                    = 8765
  to_port                      = 8765
}
resource "aws_vpc_security_group_ingress_rule" "api" {
  security_group_id            = aws_security_group.api.id
  referenced_security_group_id = aws_security_group.frontend.id
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
}
# HTTPS egress for AWS APIs, ECR, the approved IdP. Domain allowlisting requires an
# approved proxy/firewall; SGs cannot filter domain names. No unrestricted all-port rule.
locals {
  https_clients = { frontend = aws_security_group.frontend.id, api = aws_security_group.api.id, worker = aws_security_group.worker.id }
  egress        = { for pair in setproduct(keys(local.https_clients), var.https_egress_cidrs) : "${pair[0]}-${pair[1]}" => { group = local.https_clients[pair[0]], cidr = pair[1] } }
}
resource "aws_vpc_security_group_egress_rule" "https" {
  for_each          = local.egress
  security_group_id = each.value.group
  cidr_ipv4         = each.value.cidr
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_egress_rule" "s3" {
  for_each          = { api = aws_security_group.api.id, worker = aws_security_group.worker.id }
  security_group_id = each.value
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_egress_rule" "to_api" {
  security_group_id            = aws_security_group.frontend.id
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
}
output "vpc_id" { value = aws_vpc.this.id }
output "private_subnet_ids" { value = aws_subnet.private[*].id }
output "api_security_group_id" { value = aws_security_group.api.id }
output "worker_security_group_id" { value = aws_security_group.worker.id }
output "migration_security_group_id" { value = aws_security_group.migration.id }
